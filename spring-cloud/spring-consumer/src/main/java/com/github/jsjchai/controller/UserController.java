package com.github.jsjchai.controller;


import com.alibaba.fastjson.JSONObject;
import com.github.jsjchai.model.User;
import com.github.jsjchai.service.UserCilentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@RestController
@RequestMapping("/user")
@Slf4j
public class UserController {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private UserCilentService userCilentService;


    @GetMapping(value = "/user-rest")
    public List<User> rest() {
        log.info("user-rest");
        return restTemplate.getForObject("http://consul-demo-provider/user/findAll", List.class);
    }

    @GetMapping(value = "/products")
    public Object listProducts() {
        log.info("products-rest");
        return restTemplate.getForObject("http://vertx-service/products", List.class);
    }

    @GetMapping(value = "/list")
    public Object listUsers() {
        log.info("products-rest");
        JSONObject jo = new JSONObject();
        jo.put("code", 200);
        jo.put("data", "list user");
        return jo;
    }

    @GetMapping(value = "/user-feign")
    public List<User> feign() {
        log.info("user-feign");
        return userCilentService.findAll();
    }
}
