-- ===================================================================
-- V21 加入 sourcing_candidate 欄位：product_id、lead_time_overridden_by
-- 對應：實體 SourcingCriterion 的 product 與 leadTimeOverriddenBy 欄位
-- ===================================================================

ALTER TABLE sourcing_candidate
    ADD COLUMN product_id BIGINT NOT NULL,
    ADD CONSTRAINT uk_sourcing_candidate_product_id UNIQUE (product_id),
    ADD CONSTRAINT fk_sourcing_candidate_product
        FOREIGN KEY (product_id) REFERENCES product (id);

ALTER TABLE sourcing_candidate
    ADD COLUMN lead_time_overridden_by BIGINT,
    ADD CONSTRAINT fk_sourcing_candidate_lead_time_overridden_by
        FOREIGN KEY (lead_time_overridden_by) REFERENCES app_user (id);
