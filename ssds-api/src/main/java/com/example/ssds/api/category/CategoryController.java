package com.example.ssds.api.category;

import com.example.ssds.api.category.dto.CategoryTreeResponse;
import com.example.ssds.api.common.response.ApiResponse;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /**
     * 取得所有品類階層樹狀列表。
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<CategoryTreeResponse>> getCategories() {
        return ApiResponse.success(categoryService.getCategoryTree());
    }
}
