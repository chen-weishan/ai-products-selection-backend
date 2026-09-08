package com.example.ssds.api.category;

import com.example.ssds.api.category.dto.CategoryTreeResponse;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.CategoryLeadTime;
import com.example.ssds.infra.repository.CategoryLeadTimeRepository;
import com.example.ssds.infra.repository.CategoryRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final CategoryLeadTimeRepository leadTimeRepository;

    public CategoryService(CategoryRepository categoryRepository, CategoryLeadTimeRepository leadTimeRepository) {
        this.categoryRepository = categoryRepository;
        this.leadTimeRepository = leadTimeRepository;
    }

    /**
     * 取得兩層品類樹結構，並帶入各品類的前置天數。
     */
    public List<CategoryTreeResponse> getCategoryTree() {
        Map<Long, Integer> leadTimeMap = leadTimeRepository.findAll().stream()
                .collect(Collectors.toMap(CategoryLeadTime::getCategoryId, CategoryLeadTime::getLeadTimeDays));

        List<Category> rootCategories = categoryRepository.findTreeWithChildren();

        return rootCategories.stream()
                .sorted(Comparator.comparingInt(Category::getSortOrder))
                .map(root -> toResponse(root, leadTimeMap))
                .toList();
    }

    private CategoryTreeResponse toResponse(Category category, Map<Long, Integer> leadTimeMap) {
        List<CategoryTreeResponse> childResponses = null;
        if (category.getChildren() != null && !category.getChildren().isEmpty()) {
            childResponses = category.getChildren().stream()
                    .sorted(Comparator.comparingInt(Category::getSortOrder))
                    .map(child -> toResponse(child, leadTimeMap))
                    .toList();
        }
        Integer leadTimeDays = leadTimeMap.get(category.getId());
        return new CategoryTreeResponse(
                category.getId(),
                category.getName(),
                leadTimeDays,
                childResponses
        );
    }
}
