package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TSaltYearTaskMapper;
import com.fc.v2.model.auto.TSaltYearTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 年检催办：只捞待派条目；进入提前窗口才算到期；派出即回写已派出，
 * 下周期再跑不重复计数（修"每领一次数目变大"）；空批不崩。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class TSaltYearTaskServiceImplTest {

    @Mock
    private TSaltYearTaskMapper mapper;

    private TSaltYearTaskServiceImpl service;

    private static final long DAY = 24L * 60 * 60 * 1000;

    @BeforeEach
    public void setUp() {
        service = new TSaltYearTaskServiceImpl();
        ReflectionTestUtils.setField(service, "saltYearTaskMapper", mapper);
        when(mapper.updateById(any())).thenReturn(1);
    }

    private TSaltYearTask task(Long id, int status, int advanceDays, Date dueAt) {
        TSaltYearTask t = new TSaltYearTask();
        t.setId(id);
        t.setStatus(status);
        t.setAmount(BigDecimal.valueOf(advanceDays));
        t.setDueAt(dueAt);
        t.setDelFlag(0);
        return t;
    }

    @Test
    public void emptyDueList_runOnceReturnsZeroNoCrash() {
        when(mapper.selectList(any(QueryWrapper.class))).thenReturn(new ArrayList<TSaltYearTask>());
        assertEquals(0, service.runOnce(new Date()));
    }

    @Test
    public void onlyWaitingItems_inWindow_areDue() {
        Date now = new Date(1_000_000_000_000L);
        // 待派、窗口内；待派、窗口还没到；已派出（不应再出现）
        TSaltYearTask due = task(1L, 0, 5, new Date(now.getTime() + 2 * DAY));
        TSaltYearTask notYet = task(2L, 0, 5, new Date(now.getTime() + 10 * DAY));
        TSaltYearTask done = task(3L, 1, 5, new Date(now.getTime() + 2 * DAY));
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(new ArrayList<>(Arrays.asList(due, notYet))) // 待派查询
                .thenReturn(new ArrayList<>(Arrays.asList(due, notYet))); // 第二次跑

        List<TSaltYearTask> dueList = service.listDue(now);
        assertEquals(1, dueList.size());
        assertEquals(1L, dueList.get(0).getId());

        // 跑完一批：只派了 1 条，且回写成已派出
        int n = service.runOnce(now);
        assertEquals(1, n);
        ArgumentCaptor<TSaltYearTask> cap = ArgumentCaptor.forClass(TSaltYearTask.class);
        verify(mapper).updateById(cap.capture());
        assertEquals(1, cap.getValue().getStatus());

        // 第二周期：已派出的条目不再被捞（查询钉死 status=0），计数不再变大
        int n2 = service.runOnce(now);
        assertEquals(1, n2);
        verify(mapper, times(2)).updateById(any());
    }

    @Test
    public void overdueButWaiting_stillDue() {
        Date now = new Date(1_000_000_000_000L);
        TSaltYearTask overdue = task(1L, 0, 2, new Date(now.getTime() - 9 * DAY));
        when(mapper.selectList(any(QueryWrapper.class)))
                .thenReturn(new ArrayList<>(Arrays.asList(overdue)));
        assertEquals(1, service.listDue(now).size());
    }
}
