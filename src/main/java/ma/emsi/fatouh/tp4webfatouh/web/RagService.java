package ma.emsi.fatouh.tp4webfatouh.web;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.content.retriever.WebSearchContentRetriever;
import dev.langchain4j.rag.query.router.LanguageModelQueryRouter;
import dev.langchain4j.rag.query.router.QueryRouter;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.web.search.tavily.TavilyWebSearchEngine;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.*;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class RagService {

    public interface Assistant {
        String chat(String msg);
    }

    private ChatModel model;
    private EmbeddingStore<TextSegment> storePdf1, storePdf2;
    private ContentRetriever retrieverPdf1, retrieverPdf2, retrieverWeb;
    private QueryRouter multiPdfRouter;
    private TavilyWebSearchEngine tavily;

    @PostConstruct
    void init() {
        Logger log = Logger.getLogger("dev.langchain4j");
        log.setLevel(Level.FINE);
        ConsoleHandler h = new ConsoleHandler();
        h.setLevel(Level.FINE);
        log.addHandler(h);

        String geminiKey = System.getenv("GEMINI_KEY");
        model = GoogleAiGeminiChatModel.builder()
                .apiKey(geminiKey)
                .modelName("gemini-2.5-flash")
                .temperature(0.2)
                .logRequestsAndResponses(true)
                .build();

        storePdf1 = new InMemoryEmbeddingStore<>();
        storePdf2 = new InMemoryEmbeddingStore<>();
        ingest("docs/rag.pdf", storePdf1);
        ingest("docs/ml.pdf", storePdf2);

        EmbeddingModel emb = new AllMiniLmL6V2EmbeddingModel();
        retrieverPdf1 = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(storePdf1)
                .embeddingModel(emb)
                .maxResults(3)
                .minScore(0.5)
                .build();

        retrieverPdf2 = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(storePdf2)
                .embeddingModel(emb)
                .maxResults(3)
                .minScore(0.5)
                .build();

        Map<ContentRetriever, String> descriptions = new LinkedHashMap<>();
        descriptions.put(retrieverPdf1, "Support de cours RAG, Fine-tuning, LLMs, IA appliquée.");
        descriptions.put(retrieverPdf2, "Notes IA2/M2 MIAGE : complexité, bases IA, modèles, bonnes pratiques.");

        multiPdfRouter = new LanguageModelQueryRouter(model, descriptions);

        String tavilyKey = System.getenv("TAVILY_KEY");
        if (tavilyKey != null && !tavilyKey.isBlank()) {
            tavily = TavilyWebSearchEngine.builder().apiKey(tavilyKey).build();
            retrieverWeb = WebSearchContentRetriever.builder()
                    .webSearchEngine(tavily)
                    .maxResults(3)
                    .build();
        }
    }

    private void ingest(String resource, EmbeddingStore<TextSegment> store) {
        Document doc = ClassPathDocumentLoader.loadDocument(resource, new ApacheTikaDocumentParser());
        DocumentSplitter splitter = DocumentSplitters.recursive(300, 30);
        List<TextSegment> segments = splitter.split(doc);

        EmbeddingModel embeddingModel = new AllMiniLmL6V2EmbeddingModel();
        var embeddings = embeddingModel.embedAll(segments).content();

        store.addAll(embeddings, segments);
    }

    public Assistant buildAssistant(boolean useWeb, boolean guardNoRag, boolean multiPdf) {

        List<ContentRetriever> pool = new ArrayList<>();
        if (multiPdf) {
        } else {
            pool.add(retrieverPdf1);
            pool.add(retrieverPdf2);
        }
        if (useWeb && retrieverWeb != null) pool.add(retrieverWeb);

        QueryRouter router;

        if (guardNoRag) {

            ChatModel classifier = GoogleAiGeminiChatModel.builder()
                    .apiKey(System.getenv("GEMINI_KEY"))
                    .modelName("gemini-2.5-flash")
                    .temperature(0.0)
                    .logRequestsAndResponses(true)
                    .build();

            router = query -> {

                String prompt = """
            Tu es un classifieur STRICT.
            Réponds par un seul mot : oui, non ou peut-être.
            Est-ce que la requête suivante concerne l'IA, le RAG, les LLM ou le Machine Learning ?
            Requête : "%s"
            Réponse :
        """.formatted(query.text());

                String raw = classifier.chat(prompt);
                if (raw == null) return List.of();

                String decision = raw.toLowerCase()
                        .replaceAll("[^a-zàâçéèêëîïôûùüÿñœ]", " ")
                        .trim()
                        .split("\\s+")[0];

                boolean isYes = "oui".equals(decision);

                if (!isYes) {
                    return List.of();
                }

                return List.of(retrieverPdf1);
            };
        }
        else {
            router = (multiPdf)
                    ? new LanguageModelQueryRouter(model, Map.of(
                    retrieverPdf1, "Support de cours RAG, Fine-tuning, LLMs, IA appliquée.",
                    retrieverPdf2, "Support de cours Machine Learning : régression, classification, overfitting, etc."
            ))
                    : query -> pool;
        }

        RetrievalAugmentor augmentor = DefaultRetrievalAugmentor.builder()
                .queryRouter(router)
                .build();

        return AiServices.builder(Assistant.class)
                .chatModel(model)
                .retrievalAugmentor(augmentor)
                .build();
    }
}

