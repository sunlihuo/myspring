package com.github.service;

import com.github.annotation.MyService;

@MyService
public class DemoService implements IDemoService {

    @Override
    public String get(String name) {
        return "My name is " + name;
    }
}
