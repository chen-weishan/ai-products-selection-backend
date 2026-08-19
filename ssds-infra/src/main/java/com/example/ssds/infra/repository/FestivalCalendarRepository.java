package com.example.ssds.infra.repository;

import com.example.ssds.infra.entity.FestivalCalendar;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** 節慶檔期（規格書 §7.2 festival_calendar、FR-17-1）。 */
@Repository
public interface FestivalCalendarRepository extends JpaRepository<FestivalCalendar, Long> {

    Optional<FestivalCalendar> findByFestivalCodeAndYear(String festivalCode, int year);

    List<FestivalCalendar> findByYearOrderByFestivalDateAsc(int year);

    /** 時間窗計算只需要「今天之後、備貨期之內」的節慶，不必整年掃。 */
    List<FestivalCalendar> findByFestivalDateBetweenOrderByFestivalDateAsc(
            LocalDate from, LocalDate to);
}
