package com.supermarket.controller;

import com.supermarket.dto.ApiResponse;
import com.supermarket.model.Category;
import com.supermarket.repository.CategoryRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/categories")
public class CategoryController {

    private final CategoryRepository categoryRepository;

    public CategoryController(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @GetMapping
    public ApiResponse<List<Category>> findAll() {
        return ApiResponse.ok("Categories loaded", categoryRepository.findAll());
    }
}
