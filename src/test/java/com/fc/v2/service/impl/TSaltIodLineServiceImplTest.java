package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TSaltIodLineMapper;
import com.fc.v2.model.auto.TSaltIodLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 碘线在场口径：未删、未停用、落在生效窗；多线按优先级降序取首。
 * 停用线 evaluate/usable/evaluateTop 一律不认。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class TSaltIodLineServiceImplTest {

    @Mock
    private TSaltIodLineMapper mapper;

    private TSaltIodLineServiceImpl service;

    private final Date at = new Date(1_000_000_000_000L);

    @BeforeEach
    public void setUp() {
        service = new TSaltIodLineServiceImpl();
        ReflectionTestUtils.setField(service, "saltIodLineMapper", mapper);
    }

    private TSaltIodLine line(Long id, String code, int status, int priority,
                              Date start, Date end, String t1, String t2, String t3) {
        TSaltIodLine r = new TSaltIodLine();
        r.setId(id);
        r.setRuleCode(code);
        r.setStatus(status);
        r.setPriority(priority);
        r.setEffStart(start);
        r.setEffEnd(end);
        r.setTh1Max(new BigDecimal(t1));
        r.setTh2Max(new BigDecimal(t2));
        r.setTh3Max(new BigDecimal(t3));
        r.setDelFlag(0);
        return r;
    }

    @Test
    public void listAvailable_filtersStatusAndWindow_andSortsByPriority() {
        long day = 24L * 60 * 60 * 1000;
        TSaltIodLine active = line(1L, "A", 0, 5,
                new Date(at.getTime() - day), new Date(at.getTime() + day), "5", "20", "30");
        TSaltIodLine stopped = line(2L, "B", 1, 9, // 停用，优先级再高也不进
                new Date(at.getTime() - day), new Date(at.getTime() + day), "5", "20", "30");
        TSaltIodLine notYet = line(3L, "C", 0, 9, // 还没立线
                new Date(at.getTime() + day), new Date(at.getTime() + 2 * day), "5", "20", "30");
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(new ArrayList<>(Arrays.asList(active, stopped, notYet)));

        List<TSaltIodLine> avail = service.listAvailable(at);
        assertEquals(1, avail.size());
        assertEquals("A", avail.get(0).getRuleCode());
        assertEquals(1, service.countAvailable(at));
    }

    @Test
    public void evaluateTop_picksHigherPriorityRule() {
        long day = 24L * 60 * 60 * 1000;
        TSaltIodLine low = line(1L, "LOW", 0, 1,
                new Date(at.getTime() - day), new Date(at.getTime() + day), "5", "20", "30");
        TSaltIodLine high = line(2L, "HIGH", 0, 9,
                new Date(at.getTime() - day), new Date(at.getTime() + day), "8", "21", "33");
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(new ArrayList<>(Arrays.asList(low, high)));

        // 6 对低档是2档，对高档是1档——取高档应判1
        assertEquals(1, service.evaluateTop(new BigDecimal("6"), at));
    }

    @Test
    public void evaluate_stoppedOrOutOfWindow_returnsZero() {
        long day = 24L * 60 * 60 * 1000;
        TSaltIodLine stopped = line(1L, "S", 1, 5,
                new Date(at.getTime() - day), new Date(at.getTime() + day), "5", "20", "30");
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(new ArrayList<>(Arrays.asList(stopped)));
        assertEquals(0, service.evaluate("S", new BigDecimal("3"), at));
        assertFalse(service.usable(1L, at));
    }

    @Test
    public void usable_deletedOrNull_returnsFalse() {
        TSaltIodLine deleted = line(1L, "D", 0, 5, null, null, "5", "20", "30");
        deleted.setDelFlag(1);
        when(mapper.selectById(1L)).thenReturn(deleted);
        assertFalse(service.usable(1L, at));
        assertFalse(service.usable(null, at));
    }

    @Test
    public void evaluate_nullParams_returnZero() {
        assertEquals(0, service.evaluate(null, BigDecimal.ONE, at));
        assertEquals(0, service.evaluate("A", null, at));
        assertEquals(0, service.evaluate("A", BigDecimal.ONE, null));
        assertTrue(service.listAvailable(null).isEmpty());
    }
}
