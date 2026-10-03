package com.example.ssds.api.decision;

import static com.example.ssds.api.decision.DecisionFixtures.BUYER_EMAIL;
import static com.example.ssds.api.decision.DecisionFixtures.CLOCK;
import static com.example.ssds.api.decision.DecisionFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ssds.api.common.error.BusinessException;
import com.example.ssds.api.common.error.ErrorCode;
import com.example.ssds.api.decision.dto.CampaignResultRequest;
import com.example.ssds.api.decision.dto.CloseDecisionRequest;
import com.example.ssds.api.decision.dto.CreateDecisionRequest;
import com.example.ssds.api.decision.dto.DecisionResponse;
import com.example.ssds.api.decision.dto.DecisionStage;
import com.example.ssds.core.domain.DecisionType;
import com.example.ssds.core.domain.Grade;
import com.example.ssds.core.domain.InsightType;
import com.example.ssds.core.domain.ProductStatus;
import com.example.ssds.core.domain.TrackType;
import com.example.ssds.core.domain.SelloutStatus;
import com.example.ssds.infra.entity.AuditLog;
import com.example.ssds.infra.entity.CampaignResult;
import com.example.ssds.infra.entity.CampaignSnapshot;
import com.example.ssds.infra.entity.DecisionRecord;
import com.example.ssds.infra.entity.Product;
import com.example.ssds.infra.repository.AiInsightRepository;
import com.example.ssds.infra.repository.AppUserRepository;
import com.example.ssds.infra.repository.AuditLogRepository;
import com.example.ssds.infra.repository.CampaignResultRepository;
import com.example.ssds.infra.repository.DecisionRecordRepository;
import com.example.ssds.infra.repository.ProductRepository;
import com.example.ssds.infra.repository.ProductScoreRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** FR-11-1／FR-11-2 寫入規則，逐條對應 AC-11-x。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DecisionCommandServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductScoreRepository productScoreRepository;
    @Mock private AiInsightRepository aiInsightRepository;
    @Mock private DecisionRecordRepository decisionRecordRepository;
    @Mock private CampaignResultRepository campaignResultRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private DecisionSnapshotFactory snapshotFactory;

    private DecisionCommandService service;

    @BeforeEach
    void setUp() {
        service = new DecisionCommandService(productRepository, productScoreRepository, aiInsightRepository,
                decisionRecordRepository, campaignResultRepository, appUserRepository, auditLogRepository,
                snapshotFactory, CLOCK);
        when(appUserRepository.findByEmail(BUYER_EMAIL))
                .thenReturn(Optional.of(DecisionFixtures.user(1L, BUYER_EMAIL)));
        when(decisionRecordRepository.saveAndFlush(any())).thenAnswer(inv -> {
            DecisionRecord record = inv.getArgument(0);
            if (record.getId() == null) {
                record.setId(99L);
            }
            return record;
        });
        when(campaignResultRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(snapshotFactory.create(any())).thenAnswer(inv ->
                CampaignSnapshot.builder().decision(inv.getArgument(0)).build());
    }

    @Nested
    @DisplayName("建立決策")
    class Create {

        private final Product product = DecisionFixtures.product(10L);

        @BeforeEach
        void givenProductWithPrimaryScore() {
            when(productRepository.findById(10L)).thenReturn(Optional.of(product));
            when(productScoreRepository.findFirstByProductIdAndPrimaryTrueAndActiveTrueOrderByCalculatedAtDesc(10L))
                    .thenReturn(Optional.of(DecisionFixtures.score(55L, product, "86.00", Grade.A)));
        }

        private void givenAi(String json) {
            when(aiInsightRepository.findByProductIdAndInsightTypeAndCurrentTrue(10L, InsightType.RECOMMENDATION))
                    .thenReturn(Optional.of(DecisionFixtures.recommendation(7L, json)));
        }

        @Test
        @DisplayName("AC-11-1／AC-11-7：綁定主情境評分、依 AI action 判定 followed_ai、自動產生快照並留稽核")
        void bindsPrimaryScoreAndFollowsAi() {
            givenAi("{\"action\":\"ADOPT\",\"qtyMin\":250,\"qtyMax\":350,\"quantityText\":\"首批 250–350 組\"}");

            DecisionResponse response = service.create(10L,
                    new CreateDecisionRequest(DecisionType.ADOPT, 300, TODAY.plusDays(7), null),
                    BUYER_EMAIL, "127.0.0.1");

            assertThat(response.scoreId()).isEqualTo(55L);
            assertThat(response.followedAi()).isTrue();
            assertThat(response.aiAction()).isEqualTo(DecisionType.ADOPT);
            assertThat(response.aiQtyMin()).isEqualTo(250);
            assertThat(response.aiQtyMax()).isEqualTo(350);
            assertThat(response.stage()).isEqualTo(DecisionStage.AWAITING_LAUNCH);
            assertThat(response.productStatus()).isEqualTo(ProductStatus.ADOPTED);

            ArgumentCaptor<DecisionRecord> saved = ArgumentCaptor.forClass(DecisionRecord.class);
            verify(decisionRecordRepository).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getSnapshot()).isNotNull();
            assertThat(saved.getValue().getDecidedAt()).isEqualTo(Instant.now(CLOCK));

            // 兩筆稽核：決策建立 + 品項狀態轉換（§7.4）
            ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository, times(2)).save(audit.capture());
            assertThat(audit.getAllValues()).extracting(AuditLog::getAction, AuditLog::getEntityType)
                    .containsExactlyInAnyOrder(tuple("CREATE", "DecisionRecord"), tuple("UPDATE", "Product"));
            verify(productRepository).saveAndFlush(product);
            assertThat(product.getStatus()).isEqualTo(ProductStatus.ADOPTED);
        }

        @Test
        @DisplayName("§7.4：淘汰決策把品項轉為 REJECTED，決策理由同步為品項的 reject_reason")
        void rejectMovesProductToRejected() {
            givenAi("{\"action\":\"REJECT\",\"qtyMin\":0,\"qtyMax\":0}");

            service.create(10L, new CreateDecisionRequest(DecisionType.REJECT, null, null, "評論風險過高"),
                    BUYER_EMAIL, null);

            assertThat(product.getStatus()).isEqualTo(ProductStatus.REJECTED);
            assertThat(product.getRejectReason()).isEqualTo("評論風險過高");
        }

        @Test
        @DisplayName("§7.4：表外轉換回 409，且不寫入任何決策")
        void rejectsTransitionOutsideStateMachine() {
            product.setStatus(ProductStatus.ADOPTED);

            assertThatThrownBy(() -> service.create(10L,
                    new CreateDecisionRequest(DecisionType.REJECT, null, null, "改變主意"), BUYER_EMAIL, null))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
            verify(decisionRecordRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("AC-11-1：沒有主情境評分時不可建立決策")
        void rejectsWithoutPrimaryScore() {
            when(productScoreRepository.findFirstByProductIdAndPrimaryTrueAndActiveTrueOrderByCalculatedAtDesc(10L))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(10L,
                    new CreateDecisionRequest(DecisionType.WATCH, null, null, "先觀察"), BUYER_EMAIL, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
            verify(decisionRecordRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("AC-11-2：與 AI 建議不同且未填理由時拒絕，錯誤指向 reason 欄位")
        void requiresReasonWhenNotFollowingAi() {
            givenAi("{\"action\":\"WATCH\",\"qtyMin\":0,\"qtyMax\":0}");

            assertThatThrownBy(() -> service.create(10L,
                    new CreateDecisionRequest(DecisionType.ADOPT, 300, null, "   "), BUYER_EMAIL, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> {
                        BusinessException be = (BusinessException) e;
                        assertThat(be.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        assertThat(be.getFieldErrors()).extracting("field").containsExactly("reason");
                    });
        }

        @Test
        @DisplayName("AC-11-2：與 AI 不同但有理由時可建立，followed_ai = false")
        void acceptsDisagreementWithReason() {
            givenAi("{\"action\":\"WATCH\",\"qtyMin\":0,\"qtyMax\":0}");

            DecisionResponse response = service.create(10L,
                    new CreateDecisionRequest(DecisionType.ADOPT, 300, null, "中秋檔期需要"), BUYER_EMAIL, null);

            assertThat(response.followedAi()).isFalse();
            assertThat(response.aiAction()).isEqualTo(DecisionType.WATCH);
            assertThat(response.reason()).isEqualTo("中秋檔期需要");
        }

        @Test
        @DisplayName("沒有 AI 建議時視同未採納：理由必填，ai_action 為 null")
        void noAiRecommendationRequiresReason() {
            assertThatThrownBy(() -> service.create(10L,
                    new CreateDecisionRequest(DecisionType.ADOPT, 300, null, null), BUYER_EMAIL, null))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);

            DecisionResponse response = service.create(10L,
                    new CreateDecisionRequest(DecisionType.ADOPT, 300, null, "人工判斷"), BUYER_EMAIL, null);
            assertThat(response.aiAction()).isNull();
            assertThat(response.followedAi()).isFalse();
        }

        @Test
        @DisplayName("ADOPT 必填首批數量（資料庫 ck_decision_qty）")
        void adoptRequiresQuantity() {
            givenAi("{\"action\":\"ADOPT\",\"qtyMin\":100,\"qtyMax\":200}");

            assertThatThrownBy(() -> service.create(10L,
                    new CreateDecisionRequest(DecisionType.ADOPT, null, null, null), BUYER_EMAIL, null))
                    .satisfies(e -> assertThat(((BusinessException) e).getFieldErrors())
                            .extracting("field").containsExactly("firstOrderQty"));
        }

        @Test
        @DisplayName("WATCH／REJECT 不開團：數量與上架日不落地")
        void watchDropsCampaignFields() {
            givenAi("{\"action\":\"WATCH\",\"qtyMin\":0,\"qtyMax\":0}");

            DecisionResponse response = service.create(10L,
                    new CreateDecisionRequest(DecisionType.WATCH, 300, TODAY, null), BUYER_EMAIL, null);

            assertThat(response.firstOrderQty()).isNull();
            assertThat(response.expectedListDate()).isNull();
            assertThat(response.stage()).isEqualTo(DecisionStage.NO_CAMPAIGN);
        }

        @Test
        @DisplayName("已刪除的品項回 404")
        void deletedProductNotFound() {
            product.setDeletedAt(Instant.parse("2026-09-01T00:00:00Z"));

            assertThatThrownBy(() -> service.create(10L,
                    new CreateDecisionRequest(DecisionType.WATCH, null, null, "x"), BUYER_EMAIL, null))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        }

        @Test
        @DisplayName("未登入（actor 為 null）回 401")
        void anonymousRejected() {
            assertThatThrownBy(() -> service.create(10L,
                    new CreateDecisionRequest(DecisionType.WATCH, null, null, "x"), null, null))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("建立決策前的判斷依據（decision-context）")
    class Context {

        private final Product product = DecisionFixtures.product(20L);

        @BeforeEach
        void givenProduct() {
            when(productRepository.findById(20L)).thenReturn(Optional.of(product));
        }

        @Test
        @DisplayName("有評分與 AI 建議時列出綁定評分、AI 建議與 §7.4 可選決策")
        void listsAllowedDecisions() {
            product.setStatus(ProductStatus.WATCHING);
            when(productScoreRepository.findFirstByProductIdAndPrimaryTrueAndActiveTrueOrderByCalculatedAtDesc(20L))
                    .thenReturn(Optional.of(DecisionFixtures.score(77L, product, "88.00", Grade.A)));
            when(aiInsightRepository.findByProductIdAndInsightTypeAndCurrentTrue(20L, InsightType.RECOMMENDATION))
                    .thenReturn(Optional.of(DecisionFixtures.recommendation(1L,
                            "{\"action\":\"ADOPT\",\"qtyMin\":250,\"qtyMax\":350,"
                                    + "\"quantityText\":\"首批 250–350 組\",\"reasoning\":\"熱度穩定\"}")));

            var context = service.context(20L);

            assertThat(context.score().scoreId()).isEqualTo(77L);
            assertThat(context.ai().action()).isEqualTo(DecisionType.ADOPT);
            assertThat(context.ai().quantityText()).isEqualTo("首批 250–350 組");
            assertThat(context.allowedDecisions()).containsExactly(DecisionType.ADOPT, DecisionType.REJECT);
            assertThat(context.blockedReason()).isNull();
        }

        @Test
        @DisplayName("沒有主情境評分時不可建立任何決策，並說明原因")
        void blockedWithoutScore() {
            var context = service.context(20L);

            assertThat(context.score()).isNull();
            assertThat(context.ai()).isNull();
            assertThat(context.allowedDecisions()).isEmpty();
            assertThat(context.blockedReason()).contains("尚無主情境評分");
        }

        @Test
        @DisplayName("已上架品項：有評分但狀態不允許，回傳狀態機的原因")
        void blockedByStateMachine() {
            product.setStatus(ProductStatus.LISTED);
            when(productScoreRepository.findFirstByProductIdAndPrimaryTrueAndActiveTrueOrderByCalculatedAtDesc(20L))
                    .thenReturn(Optional.of(DecisionFixtures.score(77L, product, "88.00", Grade.A)));

            var context = service.context(20L);

            assertThat(context.allowedDecisions()).isEmpty();
            assertThat(context.blockedReason()).contains("LISTED");
        }
    }

    @Nested
    @DisplayName("§7.4 品項狀態轉換表")
    class StateMachine {

        private ProductStatus next(ProductStatus source, DecisionType decision) {
            Product product = DecisionFixtures.product(1L);
            product.setStatus(source);
            return DecisionCommandService.nextProductStatus(product, decision);
        }

        @Test
        @DisplayName("EVALUATING 可轉三種；WATCHING 可轉 ADOPT／REJECT")
        void allowedTransitions() {
            assertThat(next(ProductStatus.EVALUATING, DecisionType.WATCH)).isEqualTo(ProductStatus.WATCHING);
            assertThat(next(ProductStatus.EVALUATING, DecisionType.ADOPT)).isEqualTo(ProductStatus.ADOPTED);
            assertThat(next(ProductStatus.EVALUATING, DecisionType.REJECT)).isEqualTo(ProductStatus.REJECTED);
            assertThat(next(ProductStatus.WATCHING, DecisionType.ADOPT)).isEqualTo(ProductStatus.ADOPTED);
            assertThat(next(ProductStatus.WATCHING, DecisionType.REJECT)).isEqualTo(ProductStatus.REJECTED);
        }

        @Test
        @DisplayName("WATCHING 再 WATCH、DRAFT／ADOPTED／LISTED／REJECTED 出發、B 軌品項都回 409")
        void forbiddenTransitions() {
            for (ProductStatus source : new ProductStatus[] { ProductStatus.DRAFT, ProductStatus.ADOPTED,
                    ProductStatus.LISTED, ProductStatus.REJECTED }) {
                assertThatThrownBy(() -> next(source, DecisionType.ADOPT))
                        .extracting(e -> ((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
            }
            assertThatThrownBy(() -> next(ProductStatus.WATCHING, DecisionType.WATCH))
                    .isInstanceOf(BusinessException.class);

            Product trackB = DecisionFixtures.product(2L);
            trackB.setTrackType(TrackType.B);
            assertThatThrownBy(() -> DecisionCommandService.nextProductStatus(trackB, DecisionType.ADOPT))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("AI 建議解析")
    class ParseRecommendation {

        @Test
        @DisplayName("內容壞掉時視同無 AI 建議，不擋決策")
        void malformedJsonIsTreatedAsNone() {
            assertThat(DecisionCommandService.parse(DecisionFixtures.recommendation(1L, "{not json")))
                    .isEqualTo(DecisionCommandService.AiRecommendation.NONE);
            assertThat(DecisionCommandService.parse(DecisionFixtures.recommendation(1L, "{\"action\":\"TRIAL\"}")))
                    .isEqualTo(DecisionCommandService.AiRecommendation.NONE);
        }

        @Test
        @DisplayName("數量區間顛倒時作廢區間、保留動作（ck_decision_ai_qty_range）")
        void invertedQuantityRangeIsDropped() {
            var parsed = DecisionCommandService.parse(
                    DecisionFixtures.recommendation(1L, "{\"action\":\"ADOPT\",\"qtyMin\":500,\"qtyMax\":100}"));
            assertThat(parsed.action()).isEqualTo(DecisionType.ADOPT);
            assertThat(parsed.qtyMin()).isNull();
            assertThat(parsed.qtyMax()).isNull();
        }
    }

    @Nested
    @DisplayName("結案")
    class Close {

        @Test
        @DisplayName("AC-11-8：WATCH／REJECT 不可結案")
        void onlyAdoptCanClose() {
            for (DecisionType type : new DecisionType[] { DecisionType.WATCH, DecisionType.REJECT }) {
                when(decisionRecordRepository.findById(1L))
                        .thenReturn(Optional.of(DecisionFixtures.decision(1L, type)));
                assertThatThrownBy(() -> service.close(1L, new CloseDecisionRequest(TODAY), BUYER_EMAIL, null))
                        .extracting(e -> ((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
            }
        }

        @Test
        @DisplayName("§7.4：品項尚未上架（ADOPTED）時不可結案")
        void requiresListedProduct() {
            DecisionRecord record = DecisionFixtures.decision(1L, DecisionType.ADOPT);
            record.getProduct().setStatus(ProductStatus.ADOPTED);
            when(decisionRecordRepository.findById(1L)).thenReturn(Optional.of(record));

            assertThatThrownBy(() -> service.close(1L, null, BUYER_EMAIL, null))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        }

        @Test
        @DisplayName("未指定日期時以今日結案，並寫稽核")
        void defaultsToToday() {
            when(decisionRecordRepository.findById(1L))
                    .thenReturn(Optional.of(DecisionFixtures.decision(1L, DecisionType.ADOPT)));

            DecisionResponse response = service.close(1L, null, BUYER_EMAIL, null);

            assertThat(response.campaignEndDate()).isEqualTo(TODAY);
            assertThat(response.stage()).isEqualTo(DecisionStage.PENDING_RESULT);
            ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogRepository).save(audit.capture());
            assertThat(audit.getValue().getAction()).isEqualTo("CLOSE");
        }

        @Test
        @DisplayName("§FR-11-2：可回填過去日期（含早於決策日），不可填未來日期")
        void rejectsFutureDate() {
            when(decisionRecordRepository.findById(1L))
                    .thenReturn(Optional.of(DecisionFixtures.decision(1L, DecisionType.ADOPT)));

            // 決策日為 2026-09-01，規格未限制結案日下限
            assertThat(service.close(1L, new CloseDecisionRequest(TODAY.minusDays(60)), BUYER_EMAIL, null)
                    .campaignEndDate()).isEqualTo(TODAY.minusDays(60));
            assertThatThrownBy(() -> service.close(1L, new CloseDecisionRequest(TODAY.plusDays(1)), BUYER_EMAIL, null))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_FAILED);
        }

        @Test
        @DisplayName("已回填的決策不可再改結案日")
        void cannotCloseAfterResult() {
            DecisionRecord record = DecisionFixtures.filled(1L, "80", Grade.A, 300, SelloutStatus.ON_TIME);
            when(decisionRecordRepository.findById(1L)).thenReturn(Optional.of(record));

            assertThatThrownBy(() -> service.close(1L, new CloseDecisionRequest(TODAY), BUYER_EMAIL, null))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        }
    }

    @Nested
    @DisplayName("回填")
    class FillResult {

        private final CampaignResultRequest request = new CampaignResultRequest(
                412, SelloutStatus.EARLY_SELLOUT, new BigDecimal("0.0120"), new BigDecimal("0.3940"), null, null);

        @Test
        @DisplayName("未結案不可回填")
        void requiresClose() {
            when(decisionRecordRepository.findById(1L))
                    .thenReturn(Optional.of(DecisionFixtures.decision(1L, DecisionType.ADOPT)));

            assertThatThrownBy(() -> service.fillResult(1L, request, BUYER_EMAIL, null))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        }

        @Test
        @DisplayName("AC-11-8：WATCH 不提供回填")
        void watchCannotFill() {
            when(decisionRecordRepository.findById(1L))
                    .thenReturn(Optional.of(DecisionFixtures.decision(1L, DecisionType.WATCH)));

            assertThatThrownBy(() -> service.fillResult(1L, request, BUYER_EMAIL, null))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        }

        @Test
        @DisplayName("已結案者可回填一次，閉環完成；第二次回 DUPLICATE_RESOURCE")
        void fillsOnce() {
            DecisionRecord record = DecisionFixtures.decision(1L, DecisionType.ADOPT);
            record.setCampaignEndDate(TODAY.minusDays(3));
            when(decisionRecordRepository.findById(1L)).thenReturn(Optional.of(record));

            DecisionResponse response = service.fillResult(1L, request, BUYER_EMAIL, null);

            assertThat(response.stage()).isEqualTo(DecisionStage.COMPLETED);
            assertThat(response.result().actualQty()).isEqualTo(412);
            ArgumentCaptor<CampaignResult> saved = ArgumentCaptor.forClass(CampaignResult.class);
            verify(campaignResultRepository).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getRealizedMarginRate()).isEqualByComparingTo("0.394");

            assertThatThrownBy(() -> service.fillResult(1L, request, BUYER_EMAIL, null))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
        }
    }

    @Nested
    @DisplayName("覆核")
    class Review {

        @Test
        @DisplayName("記錄覆核者與時間；已覆核者不可重複覆核")
        void reviewsOnce() {
            DecisionRecord record = DecisionFixtures.decision(1L, DecisionType.WATCH);
            when(decisionRecordRepository.findById(1L)).thenReturn(Optional.of(record));

            DecisionResponse response = service.review(1L, BUYER_EMAIL, null);

            assertThat(response.reviewedById()).isEqualTo(1L);
            assertThat(response.reviewedAt()).isNotNull();
            assertThatThrownBy(() -> service.review(1L, BUYER_EMAIL, null))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
        }
    }
}
