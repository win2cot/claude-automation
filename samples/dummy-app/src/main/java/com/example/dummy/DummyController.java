package com.example.dummy;

import java.util.List;

/**
 * 検証用ダミーコントローラ。
 * 実際には Spring Boot 等のフレームワークなしで動くプレースホルダ。
 */
public class DummyController {

    private final DummyService service;

    public DummyController(DummyService service) {
        this.service = service;
    }

    public List<String> getAll() {
        return service.list();
    }

    public void create(String item) {
        service.add(item);
    }
}
