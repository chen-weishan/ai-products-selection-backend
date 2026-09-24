package com.example.ssds.api.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.product.dto.CategoryUpsertRequest;
import com.example.ssds.api.product.dto.SupplierUpsertRequest;
import com.example.ssds.infra.entity.AppUser;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.entity.Category;
import com.example.ssds.infra.entity.Supplier;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.example.ssds.infra.repository.CategoryRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.SupplierRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReferenceDataCommandServiceTest {

    private CategoryRepository categoryRepository;
    private SupplierRepository supplierRepository;
    private AuditLogRepository auditLogRepository;
    private ProductRepository productRepository;
    private ReferenceDataCommandService service;

    @BeforeEach
    void setUp() {
        categoryRepository = mock(CategoryRepository.class);
        supplierRepository = mock(SupplierRepository.class);
        AppUserRepository appUserRepository = mock(AppUserRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        productRepository = mock(ProductRepository.class);
        when(appUserRepository.findByEmail("admin@example.com"))
                .thenReturn(Optional.of(AppUser.builder()
                        .id(7L)
                        .email("admin@example.com")
                        .build()));
        service = new ReferenceDataCommandService(
                categoryRepository,
                supplierRepository,
                productRepository,
                appUserRepository,
                auditLogRepository
        );
    }

    @Test
    void createsRootCategoryAndWritesAuditSnapshot() {
        when(categoryRepository.findByNameIgnoreCase("食品"))
                .thenReturn(java.util.List.of());
        when(categoryRepository.saveAndFlush(any(Category.class)))
                .thenAnswer(invocation -> {
                    Category category = invocation.getArgument(0);
                    category.setId(11L);
                    return category;
                });

        var result = service.createCategory(
                new CategoryUpsertRequest(" 食品 ", null, 3),
                "admin@example.com",
                "127.0.0.1"
        );

        assertEquals("食品", result.name());
        assertEquals(3, result.sortOrder());
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertEquals("CREATE", audit.getValue().getAction());
        assertEquals("Category", audit.getValue().getEntityType());
    }

    @Test
    void rejectsThirdCategoryLevel() {
        Category root = Category.builder().id(1L).name("食品").build();
        Category child = Category.builder().id(2L).name("零食").parent(root).build();
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(child));

        BusinessException exception = assertThrows(BusinessException.class, () ->
                service.createCategory(
                        new CategoryUpsertRequest("糖果", 2L, 0),
                        "admin@example.com",
                        "127.0.0.1"
                ));

        assertEquals(ErrorCode.VALIDATION_FAILED, exception.getErrorCode());
    }

    @Test
    void updatesSupplierAndNormalizesOptionalFields() {
        Supplier supplier = Supplier.builder()
                .id(8L)
                .name("舊供應商")
                .contact("王小姐")
                .build();
        when(supplierRepository.findById(8L)).thenReturn(Optional.of(supplier));
        when(supplierRepository.existsByNameIgnoreCaseAndIdNot("新供應商", 8L))
                .thenReturn(false);
        when(supplierRepository.saveAndFlush(supplier)).thenReturn(supplier);

        var result = service.updateSupplier(
                8L,
                new SupplierUpsertRequest(" 新供應商 ", "  李先生  ", "  ", null),
                "admin@example.com",
                "127.0.0.1"
        );

        assertEquals("新供應商", result.name());
        assertEquals("李先生", result.contact());
        assertEquals(null, result.phone());
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertEquals("UPDATE", audit.getValue().getAction());
    }

    @Test
    void rejectsDuplicateSupplierName() {
        when(supplierRepository.existsByNameIgnoreCase("晨曦食品"))
                .thenReturn(true);

        BusinessException exception = assertThrows(BusinessException.class, () ->
                service.createSupplier(
                        new SupplierUpsertRequest("晨曦食品", null, null, null),
                        "admin@example.com",
                        "127.0.0.1"
                ));

        assertEquals(ErrorCode.DUPLICATE_RESOURCE, exception.getErrorCode());
    }

    @Test
    void softDeletesUnusedCategoryAndWritesAuditLog() {
        Category category = Category.builder().id(12L).name("飲料").build();
        when(categoryRepository.findById(12L)).thenReturn(Optional.of(category));
        when(categoryRepository.saveAndFlush(category)).thenReturn(category);

        service.deleteCategory(12L, "admin@example.com", "127.0.0.1");

        assertEquals(true, category.isDeleted());
        assertEquals(7L, category.getDeletedBy().getId());
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertEquals("DELETE", audit.getValue().getAction());
        assertEquals("Category", audit.getValue().getEntityType());
    }

    @Test
    void rejectsCategoryUsedByProducts() {
        Category category = Category.builder().id(12L).name("飲料").build();
        when(categoryRepository.findById(12L)).thenReturn(Optional.of(category));
        when(productRepository.countByCategoryId(12L)).thenReturn(3L);

        BusinessException exception = assertThrows(BusinessException.class, () ->
                service.deleteCategory(12L, "admin@example.com", "127.0.0.1"));

        assertEquals(ErrorCode.RESOURCE_IN_USE, exception.getErrorCode());
    }

    @Test
    void rejectsCategoryWithActiveChildren() {
        Category category = Category.builder().id(12L).name("食品").build();
        when(categoryRepository.findById(12L)).thenReturn(Optional.of(category));
        when(categoryRepository.countByParentIdAndDeletedAtIsNull(12L)).thenReturn(2L);

        BusinessException exception = assertThrows(BusinessException.class, () ->
                service.deleteCategory(12L, "admin@example.com", "127.0.0.1"));

        assertEquals(ErrorCode.RESOURCE_IN_USE, exception.getErrorCode());
    }

    @Test
    void softDeletesUnusedSupplierAndWritesAuditLog() {
        Supplier supplier = Supplier.builder().id(8L).name("晨曦食品").build();
        when(supplierRepository.findById(8L)).thenReturn(Optional.of(supplier));
        when(supplierRepository.saveAndFlush(supplier)).thenReturn(supplier);

        service.deleteSupplier(8L, "admin@example.com", "127.0.0.1");

        assertEquals(true, supplier.isDeleted());
        assertEquals(7L, supplier.getDeletedBy().getId());
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertEquals("DELETE", audit.getValue().getAction());
        assertEquals("Supplier", audit.getValue().getEntityType());
    }

    @Test
    void rejectsSupplierUsedByProducts() {
        Supplier supplier = Supplier.builder().id(8L).name("晨曦食品").build();
        when(supplierRepository.findById(8L)).thenReturn(Optional.of(supplier));
        when(productRepository.countBySupplierId(8L)).thenReturn(2L);

        BusinessException exception = assertThrows(BusinessException.class, () ->
                service.deleteSupplier(8L, "admin@example.com", "127.0.0.1"));

        assertEquals(ErrorCode.RESOURCE_IN_USE, exception.getErrorCode());
    }
}
