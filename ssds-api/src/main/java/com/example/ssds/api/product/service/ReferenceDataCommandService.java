package com.example.ssds.api.product.service;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.product.dto.CategoryResponse;
import com.example.ssds.api.product.dto.CategoryUpsertRequest;
import com.example.ssds.api.product.dto.SupplierResponse;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 類別與供應商主檔寫入服務。 */
@Service
@Transactional
public class ReferenceDataCommandService {

    private final CategoryRepository categoryRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final AppUserRepository appUserRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReferenceDataCommandService(
            CategoryRepository categoryRepository,
            SupplierRepository supplierRepository,
            ProductRepository productRepository,
            AppUserRepository appUserRepository,
            AuditLogRepository auditLogRepository
    ) {
        this.categoryRepository = categoryRepository;
        this.supplierRepository = supplierRepository;
        this.productRepository = productRepository;
        this.appUserRepository = appUserRepository;
        this.auditLogRepository = auditLogRepository;
    }

    public CategoryResponse createCategory(
            CategoryUpsertRequest request,
            String actorEmail,
            String sourceIp
    ) {
        AppUser actor = findActor(actorEmail);
        Category parent = findCategory(request.parentId());
        validateCategoryParent(parent);
        String name = request.name().trim();
        validateCategoryName(name, parent, null);

        Category saved = categoryRepository.saveAndFlush(Category.builder()
                .name(name)
                .parent(parent)
                .sortOrder(request.sortOrder())
                .build());
        audit(actor, "CREATE", "Category", saved.getId(), null, categoryJson(saved), sourceIp);
        return toCategoryResponse(saved);
    }

    public CategoryResponse updateCategory(
            Long id,
            CategoryUpsertRequest request,
            String actorEmail,
            String sourceIp
    ) {
        AppUser actor = findActor(actorEmail);
        Category category = requiredActiveCategory(id);
        Category parent = findCategory(request.parentId());
        validateCategoryMove(category, parent);
        String name = request.name().trim();
        validateCategoryName(name, parent, id);
        String before = categoryJson(category);

        category.setName(name);
        category.setParent(parent);
        category.setSortOrder(request.sortOrder());
        Category saved = categoryRepository.saveAndFlush(category);
        audit(actor, "UPDATE", "Category", id, before, categoryJson(saved), sourceIp);
        return toCategoryResponse(saved);
    }

    public SupplierResponse createSupplier(
            SupplierUpsertRequest request,
            String actorEmail,
            String sourceIp
    ) {
        AppUser actor = findActor(actorEmail);
        String name = request.name().trim();
        validateSupplierName(name, null);

        Supplier saved = supplierRepository.saveAndFlush(Supplier.builder()
                .name(name)
                .contact(normalize(request.contact()))
                .phone(normalize(request.phone()))
                .note(normalize(request.note()))
                .build());
        audit(actor, "CREATE", "Supplier", saved.getId(), null, supplierJson(saved), sourceIp);
        return toSupplierResponse(saved);
    }

    public SupplierResponse updateSupplier(
            Long id,
            SupplierUpsertRequest request,
            String actorEmail,
            String sourceIp
    ) {
        AppUser actor = findActor(actorEmail);
        Supplier supplier = requiredActiveSupplier(id);
        String name = request.name().trim();
        validateSupplierName(name, id);
        String before = supplierJson(supplier);

        supplier.setName(name);
        supplier.setContact(normalize(request.contact()));
        supplier.setPhone(normalize(request.phone()));
        supplier.setNote(normalize(request.note()));
        Supplier saved = supplierRepository.saveAndFlush(supplier);
        audit(actor, "UPDATE", "Supplier", id, before, supplierJson(saved), sourceIp);
        return toSupplierResponse(saved);
    }

    public void deleteCategory(Long id, String actorEmail, String sourceIp) {
        AppUser actor = findActor(actorEmail);
        Category category = requiredActiveCategory(id);
        long productCount = productRepository.countByCategoryId(id);
        long childCount = categoryRepository.countByParentIdAndDeletedAtIsNull(id);
        if (productCount > 0 || childCount > 0) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_IN_USE,
                    categoryDeleteConflictMessage(category.getName(), productCount, childCount)
            );
        }

        String before = categoryJson(category);
        category.softDelete(actor);
        categoryRepository.saveAndFlush(category);
        audit(actor, "DELETE", "Category", id, before, categoryJson(category), sourceIp);
    }

    public void deleteSupplier(Long id, String actorEmail, String sourceIp) {
        AppUser actor = findActor(actorEmail);
        Supplier supplier = requiredActiveSupplier(id);
        long productCount = productRepository.countBySupplierId(id);
        if (productCount > 0) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_IN_USE,
                    "供應商「" + supplier.getName() + "」仍被 " + productCount + " 筆品項使用，無法刪除"
            );
        }

        String before = supplierJson(supplier);
        supplier.softDelete(actor);
        supplierRepository.saveAndFlush(supplier);
        audit(actor, "DELETE", "Supplier", id, before, supplierJson(supplier), sourceIp);
    }

    private Category findCategory(Long id) {
        return id == null ? null : requiredActiveCategory(id);
    }

    private Category requiredCategory(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> notFound("類別", id));
    }

    private Category requiredActiveCategory(Long id) {
        Category category = requiredCategory(id);
        if (category.isDeleted()) {
            throw notFound("類別", id);
        }
        return category;
    }

    private Supplier requiredActiveSupplier(Long id) {
        Supplier supplier = supplierRepository.findById(id)
                .orElseThrow(() -> notFound("供應商", id));
        if (supplier.isDeleted()) {
            throw notFound("供應商", id);
        }
        return supplier;
    }

    private void validateCategoryParent(Category parent) {
        if (parent != null && parent.getParent() != null) {
            throw validation("parentId", "類別最多只能有兩層，上層類別必須是頂層類別");
        }
    }

    private void validateCategoryMove(Category category, Category parent) {
        validateCategoryParent(parent);
        if (parent != null && parent.getId().equals(category.getId())) {
            throw validation("parentId", "類別不可將自己設為上層類別");
        }
        if (parent != null && category.getChildren().stream().anyMatch(child -> !child.isDeleted())) {
            throw validation("parentId", "已有子類別的頂層類別不可再移至其他類別之下");
        }
    }

    private void validateCategoryName(String name, Category parent, Long currentId) {
        Long parentId = parent == null ? null : parent.getId();
        boolean duplicate = categoryRepository.findByNameIgnoreCase(name).stream()
                .anyMatch(category -> !category.getId().equals(currentId)
                        && Objects.equals(parentId(category), parentId));
        if (duplicate) {
            throw duplicate("同一層級已有相同名稱的類別");
        }
    }

    private void validateSupplierName(String name, Long currentId) {
        boolean duplicate = currentId == null
                ? supplierRepository.existsByNameIgnoreCase(name)
                : supplierRepository.existsByNameIgnoreCaseAndIdNot(name, currentId);
        if (duplicate) {
            throw duplicate("已有相同名稱的供應商");
        }
    }

    private AppUser findActor(String email) {
        return appUserRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.UNAUTHORIZED,
                        "登入使用者不存在或已失效"
                ));
    }

    private BusinessException notFound(String resource, Long id) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "找不到指定的" + resource + "：" + id);
    }

    private BusinessException duplicate(String message) {
        return new BusinessException(ErrorCode.DUPLICATE_RESOURCE, message);
    }

    private BusinessException validation(String field, String message) {
        return new BusinessException(
                ErrorCode.VALIDATION_FAILED,
                "主檔資料驗證失敗",
                java.util.List.of(new com.example.ssds.api.common.response.FieldError(field, message))
        );
    }

    private Long parentId(Category category) {
        return category.getParent() == null ? null : category.getParent().getId();
    }

    private CategoryResponse toCategoryResponse(Category category) {
        Category parent = category.getParent();
        return new CategoryResponse(
                category.getId(),
                category.getName(),
                parent == null ? null : parent.getId(),
                parent == null ? null : parent.getName(),
                category.getSortOrder()
        );
    }

    private SupplierResponse toSupplierResponse(Supplier supplier) {
        return new SupplierResponse(
                supplier.getId(),
                supplier.getName(),
                supplier.getContact(),
                supplier.getPhone(),
                supplier.getNote()
        );
    }

    private String categoryJson(Category category) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", category.getName());
        values.put("parentId", parentId(category));
        values.put("sortOrder", category.getSortOrder());
        values.put("deleted", category.isDeleted());
        return json(values);
    }

    private String supplierJson(Supplier supplier) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", supplier.getName());
        values.put("contact", supplier.getContact());
        values.put("phone", supplier.getPhone());
        values.put("note", supplier.getNote());
        values.put("deleted", supplier.isDeleted());
        return json(values);
    }

    private String categoryDeleteConflictMessage(String name, long productCount, long childCount) {
        java.util.List<String> usages = new java.util.ArrayList<>();
        if (productCount > 0) {
            usages.add(productCount + " 筆品項");
        }
        if (childCount > 0) {
            usages.add(childCount + " 個子類別");
        }
        return "類別「" + name + "」仍被 " + String.join("及", usages) + "使用，無法刪除";
    }

    private String json(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("無法建立主檔稽核快照", exception);
        }
    }

    private void audit(
            AppUser actor,
            String action,
            String entityType,
            Long entityId,
            String before,
            String after,
            String sourceIp
    ) {
        auditLogRepository.save(AuditLog.builder()
                .user(actor)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .beforeJson(before)
                .afterJson(after)
                .ip(sourceIp)
                .build());
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
