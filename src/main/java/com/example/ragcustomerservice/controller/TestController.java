package com.example.ragcustomerservice.controller;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test")
@RequiredArgsConstructor
public class TestController {

    private final ChatLanguageModel chatLanguageModel;
    private final EmbeddingModel embeddingModel;

    @GetMapping("/hello")
    public String hello(@RequestParam String q) {
        return chatLanguageModel.generate(q);
    }

    @GetMapping("/embed")
    public int embed(@RequestParam String text) {
        return embeddingModel.embed(text).content().dimension();
    }
}
