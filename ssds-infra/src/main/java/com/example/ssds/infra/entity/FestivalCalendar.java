package com.example.ssds.infra.entity;

import com.example.ssds.core.domain.CalendarType;
import jakarta.persistence.*;
import java.time.LocalDate;
import lombok.*;

/**
 * 節慶檔期（規格書 §7.2 festival_calendar、FR-17-1）。
 *
 * <p>AC-17-1：LUNAR 類的國曆日期須由農曆換算自動產生，不可逐年人工輸入。
 * 因此唯一鍵是 (festival_code, year)：同一個節慶每年的國曆日期不同。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "festival_calendar")
public class FestivalCalendar {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "festival_code", nullable = false, length = 32)
    private String festivalCode;

    @Column(name = "festival_name", nullable = false, length = 50)
    private String festivalName;

    @Enumerated(EnumType.STRING)
    @Column(name = "calendar_type", nullable = false, length = 8)
    private CalendarType calendarType;

    /** 換算後的國曆日期。 */
    @Column(name = "festival_date", nullable = false)
    private LocalDate festivalDate;

    /**
     * 西元年。型別為 {@code short}：v3.0 §7.2.10 指定 SMALLINT，
     * 用 int 對映會讓 ddl-auto=validate 判定型別不符而啟動失敗。
     */
    @Column(nullable = false)
    private short year;
}
