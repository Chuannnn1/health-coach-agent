package com.healthcoach.model;

import java.util.ArrayList;
import java.util.List;

public class ExecutionResult {
    public String cleanText = "";
    public List<String> patchResults = new ArrayList<>();

    public ExecutionResult() {}

    public ExecutionResult(String cleanText, List<String> patchResults) {
        this.cleanText = cleanText;
        this.patchResults = patchResults;
    }
}
