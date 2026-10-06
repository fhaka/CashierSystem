package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.model.Category;
import com.supermarket.repository.CategoryRepository;
import com.supermarket.service.SessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;

@RestController
@RequestMapping("/categories")
public class CategoryController {

    private final CategoryRepository categoryRepository;
    private final SessionService sessionService;

    public CategoryController(CategoryRepository categoryRepository, SessionService sessionService) {
        this.categoryRepository = categoryRepository;
        this.sessionService = sessionService;
    }

    @GetMapping
    public ApiResponse<List<Category>> findAll(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessionService.requireUser(token);
        return ApiResponse.ok("Categories loaded", categoryRepository.findAll());
    }
}
