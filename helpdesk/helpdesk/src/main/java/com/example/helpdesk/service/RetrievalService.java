package com.example.helpdesk.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.helpdesk.client.EmbeddingClient;
import com.example.helpdesk.common.BusinessException;
import com.example.helpdesk.dto.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ClassName:RetrievalService
 * Package:com.example.helpdesk.service
 * Description:
 *  检索服务，负责读知识库
 *  和KnowledgeBaseService分工，那个管写（上传，分块，向量化，双写），这个管读（问题，向量化，找最像的块）
 *
 *  三条检索方式：
 *      keyword--BM25g
 *      vector--向量余弦距离
 *      hybrid--混合检索
 * @Author 妄汐霜
 * @Create 2026/9/18 21:15
 * @Version 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private static final String ES_INDEX = "kb_chunk";

    /** HNSW 粗筛候选池：先快速捞 50 个"大概像"的，再在这 50 个里精确算余弦取前 k 个 */
    private static final int NUM_CANDIDATES = 50;

    //RRF平滑常数
    private static final int RRF_K = 60;

    //混合检索时每路各取多少条候选，取10条而不是k，避免单榜靠后但另一榜靠前的块被漏掉
    private static final int FUSION_CANDIDATES = 10;

    private final EmbeddingClient embeddingClient;
    private final ElasticsearchClient esClient;
    //统一入口，按mode分流，controller只调这一个方法
    public List<RetrievedChunk> search(String query,String mode,int k){
        return switch(mode){
            case "keyword" -> searchByKeyword(query,k);
            case "vector" -> searchByVector(query,k);
            default  -> searchByHybrid(query,k);  //默认走混合检索
        };
    }

    //关键词检索
    public List<RetrievedChunk> searchByKeyword(String query, int k) {


        try {
            //  第 2 步：让 ES 找最像的 k 个块
            SearchResponse<Map> resp = esClient.search(s -> s//这个search方法的两个参数含义是：第一个参数是：这么查，第二个参数是：查回来的东西用什么装
                            .index(ES_INDEX)
                            .size(k)
                            // 关键：排除 embedding 字段，否则每次检索都要从服务器白拉 1024 个 float
                            .source(src -> src.filter(f -> f.excludes("embedding")))
                            .query(q -> q.match(m -> m.field("content").query(query))), // 粗筛候选数（必须 >= k）
                    Map.class);

            // ===== 第 3 步：ES 的 Map → 业务对象 =====
            List<RetrievedChunk> result = new ArrayList<>();
            for (Hit<Map> hit : resp.hits().hits()) {
                result.add(toChunk(hit));
            }

            log.info("BM25检索完成, query={}, 命中 {} 条", query, result.size());
            return result;

        } catch (IOException e) {
            // ES 连不上（多半是 SSH 隧道断了）或查询语法错
            log.error("ES BM25检索失败, query={}", query, e);
            throw new BusinessException("检索失败：" + e.getMessage());
        }
    }

 //方式二：向量检索
    public List<RetrievedChunk> searchByVector(String query, int k) {

        //  第 1 步：问题 → 1024 维向量（必须和文档用同一个模型，否则不在一个坐标系里）
        List<Float> vector = toFloatList(embeddingClient.embed(query));

        try {
            //  第 2 步：让 ES 找最像的 k 个块
            SearchResponse<Map> resp = esClient.search(s -> s//这个search方法的两个参数含义是：第一个参数是：这么查，第二个参数是：查回来的东西用什么装
                            .index(ES_INDEX)
                            .size(k)
                            // 关键：排除 embedding 字段，否则每次检索都要从服务器白拉 1024 个 float
                            .source(src -> src.filter(f -> f.excludes("embedding")))
                            .knn(kn -> kn
                                    .field("embedding")              // 哪个字段存着向量
                                    .queryVector(vector)             // 拿什么去比
                                    .k(k)                            // 精算后要几条
                                    .numCandidates(NUM_CANDIDATES)), // 粗筛候选数（必须 >= k）
                    Map.class);

            // ===== 第 3 步：ES 的 Map → 业务对象 =====
            List<RetrievedChunk> result = new ArrayList<>();
            for (Hit<Map> hit : resp.hits().hits()) {
                result.add(toChunk(hit));
            }

            log.info("向量检索完成, query={}, 命中 {} 条", query, result.size());
            return result;

        } catch (IOException e) {
            // ES 连不上（多半是 SSH 隧道断了）或查询语法错
            log.error("ES 向量检索失败, query={}", query, e);
            throw new BusinessException("检索失败：" + e.getMessage());
        }
    }

    //方式三，混合检索
    //两路各取10条候选，用RRF合并
    //返回结果里的score时RRF融合分，不再是余弦相似度
    public List<RetrievedChunk> searchByHybrid(String query,int k){
        //第1步：两路各取10条候选
        List<RetrievedChunk> keywordHits = searchByKeyword(query,FUSION_CANDIDATES);
        List<RetrievedChunk> vectorHits = searchByVector(query,FUSION_CANDIDATES);

        //第2步，按名次倒数累加RRF分数
        Map<String,Double> rrfScore = new HashMap<>();
        Map<String,RetrievedChunk> chunkMap = new HashMap<>();
        accumulate(keywordHits,rrfScore,chunkMap);
        accumulate(vectorHits,rrfScore,chunkMap);

        //第3步。按rrf分从高到低排序
        List<RetrievedChunk> result = rrfScore.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(k)
                .map(e -> {
                    RetrievedChunk chunk = chunkMap.get(e.getKey());
                    chunk.setScore(e.getValue());
                    return chunk;
                }).toList();

        log.info("混合检索完成，query={}.候选{}+{}条，融合后取{}条",query,keywordHits.size(),vectorHits.size(),result.size());
        return result;
    }

    //把检索结果按名词累加进RRF表
    private void accumulate(List<RetrievedChunk> hits,Map<String,Double> rrfScore,Map<String,RetrievedChunk> chunkMap){
        for(int i =0;i < hits.size();i++){
            RetrievedChunk chunk = hits.get(i);

            String key = chunk.getDocumentId()+"#" + chunk.getChunkIndex();
            rrfScore.merge(key,1.0/(RRF_K +i+1),Double::sum);
            chunkMap.putIfAbsent(key,chunk);
        }
    }





















    /** 把 ES 的一条命中翻译成 RetrievedChunk */
    //DTO映射
    private RetrievedChunk toChunk(Hit<Map> hit) {
        Map<?, ?> src = hit.source() == null ? Map.of() : hit.source();

        Object rawDocId = src.get("document_id");
        Object rawChunkIndex = src.get("chunk_index");

        RetrievedChunk chunk = new RetrievedChunk();
        // 坑：JSON 反序列化出来的数字可能是 Integer 也可能是 Long，
        //     统一按 Number 转换，不要直接 (Long) 强转，否则会 ClassCastException
        chunk.setDocumentId(rawDocId == null ? null : ((Number) rawDocId).longValue());
        chunk.setDocTitle((String) src.get("doc_title"));
        chunk.setChunkIndex(rawChunkIndex == null ? null : ((Number) rawChunkIndex).intValue());
        chunk.setContent((String) src.get("content"));
        chunk.setScore(hit.score());
        return chunk;
    }

    /** float[] → List&lt;Float&gt;：ES 客户端只认 List，Java 不会自动帮我们装箱 */
    private List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add(v);
        }
        return list;
    }
}
