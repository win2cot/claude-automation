package com.example.dummy;

import java.util.ArrayList;
import java.util.List;

/**
 * 検証用ダミーサービス。
 * Claude 自動化フローのレビュー観点チェック発火対象。
 */
public class DummyService {

    private final List<String> items = new ArrayList<>();

    public void add(String item) {
        items.add(item);
    }

    public List<String> list() {
        return new ArrayList<>(items);
    }

    public int count() {
        return items.size();
    }
}
