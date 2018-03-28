package com.github.controller;

import com.github.annotation.MyAutowrited;
import com.github.annotation.MyController;
import com.github.annotation.MyRequestMapping;
import com.github.annotation.MyRequestParam;
import com.github.service.IDemoService;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@MyController
@MyRequestMapping("/demo")
public class DemoController {

    @MyAutowrited
    private IDemoService demoService;

    @MyRequestMapping("/query.json")
    public void query(HttpServletRequest request, HttpServletResponse response,
                      @MyRequestParam("name") String name) throws IOException {

        String result = demoService.get(name);
        response.getWriter().write(result);
    }

    @MyRequestMapping("/edit.json")
    public void edit(HttpServletResponse response,
                     @MyRequestParam("name") String name) throws IOException {

        String result = demoService.get(name);
        response.getWriter().write(result);
    }
}
