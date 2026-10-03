package com.example.ssds.api.score;

import com.example.ssds.infra.entity.FestivalCalendar;
import com.example.ssds.infra.entity.ScoreFactor;
import com.example.ssds.infra.entity.TrendKeyword;
import com.example.ssds.infra.repository.FestivalCalendarRepository;
import com.example.ssds.infra.repository.TrendKeywordRepository;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把 {@code score_factor.driving_keyword_id}／{@code driving_festival_id} 換成名稱，
 * 供因子長條標示「生效關鍵字／生效節慶」（AC-17-6、§5.3.3，v3.0.1 E-01／E-02）。
 *
 * <p>兩欄是純 id（{@link ScoreFactor} 沒有對應的關聯），所以名稱要另外查。
 * 一次查完整頁的 id 再組 Map，避免逐根長條查詢造成 N+1。
 * 排行、試算、詳情、FR-11 決策詳情都要這份名稱，抽成共用元件，同 {@link SceneOverrideLookup}。
 */
@Component
@RequiredArgsConstructor
public class DrivingTargetLookup {

    private final TrendKeywordRepository trendKeywordRepository;
    private final FestivalCalendarRepository festivalCalendarRepository;

    @Transactional(readOnly = true)
    public DrivingTargets resolve(Collection<ScoreFactor> factors) {
        Set<Long> keywordIds = factors.stream()
                .map(ScoreFactor::getDrivingKeywordId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> festivalIds = factors.stream()
                .map(ScoreFactor::getDrivingFestivalId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // 空集合短路：不做這件事會送出 in () 這種不合法的 SQL
        Map<Long, String> keywords = keywordIds.isEmpty() ? Map.of()
                : trendKeywordRepository.findAllById(keywordIds).stream()
                        .collect(Collectors.toMap(TrendKeyword::getId, TrendKeyword::getKeyword));
        Map<Long, String> festivals = festivalIds.isEmpty() ? Map.of()
                : festivalCalendarRepository.findAllById(festivalIds).stream()
                        .collect(Collectors.toMap(FestivalCalendar::getId, FestivalCalendar::getFestivalName));
        return new DrivingTargets(keywords, festivals);
    }

    /** id → 名稱。查不到時回 null，不擋整頁（節慶被引用時刪不掉，見 FestivalCommandService）。 */
    public record DrivingTargets(Map<Long, String> keywords, Map<Long, String> festivals) {

        public static final DrivingTargets EMPTY = new DrivingTargets(Map.of(), Map.of());

        String keyword(Long id) {
            return id == null ? null : keywords.get(id);
        }

        String festival(Long id) {
            return id == null ? null : festivals.get(id);
        }
    }
}
