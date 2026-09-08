package com.example.ssds.api.category;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.ssds.api.category.dto.CategoryTreeResponse;
import com.example.ssds.api.common.response.ApiResponse;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.CategoryLeadTime;
import com.example.ssds.infra.repository.CategoryLeadTimeRepository;
import com.example.ssds.infra.repository.CategoryRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CategoryControllerTest {

    @Test
    void shouldReturnHierarchicalCategoryTree() {
        CategoryRepository categoryRepo = mock(CategoryRepository.class);
        CategoryLeadTimeRepository leadTimeRepo = mock(CategoryLeadTimeRepository.class);

        Category food = Category.builder().id(1L).name("食品").sortOrder(1).children(new ArrayList<>()).build();
        Category snacks = Category.builder().id(10L).name("零食").parent(food).sortOrder(1).children(new ArrayList<>()).build();
        Category drinks = Category.builder().id(11L).name("飲品").parent(food).sortOrder(2).children(new ArrayList<>()).build();
        food.getChildren().add(snacks);
        food.getChildren().add(drinks);

        Category clothing = Category.builder().id(2L).name("服飾").sortOrder(2).children(new ArrayList<>()).build();

        when(categoryRepo.findTreeWithChildren()).thenReturn(List.of(food, clothing));
        when(leadTimeRepo.findAll()).thenReturn(List.of(
                CategoryLeadTime.builder().categoryId(10L).leadTimeDays(45).build(),
                CategoryLeadTime.builder().categoryId(11L).leadTimeDays(30).build()
        ));

        CategoryService service = new CategoryService(categoryRepo, leadTimeRepo);
        CategoryController controller = new CategoryController(service);

        ApiResponse<List<CategoryTreeResponse>> response = controller.getCategories();

        assertTrue(response.success());
        assertNotNull(response.data());
        assertEquals(2, response.data().size());

        CategoryTreeResponse foodResponse = response.data().get(0);
        assertEquals(1L, foodResponse.id());
        assertEquals("食品", foodResponse.name());
        assertNull(foodResponse.leadTimeDays());
        assertNotNull(foodResponse.children());
        assertEquals(2, foodResponse.children().size());

        CategoryTreeResponse snacksResponse = foodResponse.children().get(0);
        assertEquals(10L, snacksResponse.id());
        assertEquals("零食", snacksResponse.name());
        assertEquals(45, snacksResponse.leadTimeDays());
        assertNull(snacksResponse.children());

        CategoryTreeResponse drinksResponse = foodResponse.children().get(1);
        assertEquals(11L, drinksResponse.id());
        assertEquals("飲品", drinksResponse.name());
        assertEquals(30, drinksResponse.leadTimeDays());

        CategoryTreeResponse clothingResponse = response.data().get(1);
        assertEquals(2L, clothingResponse.id());
        assertEquals("服飾", clothingResponse.name());
        assertNull(clothingResponse.children());
    }
}
