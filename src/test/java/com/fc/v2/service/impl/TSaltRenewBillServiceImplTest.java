package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fc.v2.mapper.auto.TSaltRenewBillMapper;
import com.fc.v2.model.auto.TSaltRenewBill;
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
 * 换证核签：同档签齐才挪档（AND 两印），省核准签齐办结；
 * 办结不许再批；否决落挪回；退回只退一档、落印清零；头档不退。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class TSaltRenewBillServiceImplTest {

    @Mock
    private TSaltRenewBillMapper mapper;

    private TSaltRenewBillServiceImpl service;

    @BeforeEach
    public void setUp() {
        service = new TSaltRenewBillServiceImpl();
        ReflectionTestUtils.setField(service, "saltRenewBillMapper", mapper);
        when(mapper.updateById(any())).thenReturn(1);
    }

    private TSaltRenewBill bill(Long id, int node, int signMode, Integer signCount, int status) {
        TSaltRenewBill r = new TSaltRenewBill();
        r.setId(id);
        r.setNodeNo(node);
        r.setSignMode(signMode);
        r.setSignCount(signCount);
        r.setStatus(status);
        r.setDelFlag(0);
        return r;
    }

    @Test
    public void andMode_needsTwoSignsBeforeMoving() {
        when(mapper.selectById(1L)).thenReturn(bill(1L, 0, 1, 0, 0));
        TSaltRenewBill first = service.approve(1L, "甲", null);
        assertEquals(0, first.getNodeNo()); // 第一印还在县初核
        assertEquals(1, first.getSignCount());

        when(mapper.selectById(1L)).thenReturn(bill(1L, 0, 1, 1, 0));
        TSaltRenewBill second = service.approve(1L, "乙", null);
        assertEquals(1, second.getNodeNo()); // 第二印签齐，挪到市复审
        assertEquals(0, second.getSignCount()); // 下档重计
        assertEquals(0, second.getStatus());
    }

    @Test
    public void finalNode_completedOnLastSign() {
        // 省核准(node=2)，一名即可，当前 0 印
        when(mapper.selectById(1L)).thenReturn(bill(1L, 2, 0, 0, 0));
        TSaltRenewBill r = service.approve(1L, "省签批人", null);
        assertEquals(2, r.getNodeNo());
        assertEquals(1, r.getStatus()); // 办结
    }

    @Test
    public void approvedBill_cannotApproveAgain() {
        when(mapper.selectById(1L)).thenReturn(bill(1L, 2, 0, 1, 1));
        assertNull(service.approve(1L, "多事的人", null));
        verify(mapper, never()).updateById(any());
    }

    @Test
    public void anonymousApprover_rejected() {
        when(mapper.selectById(1L)).thenReturn(bill(1L, 0, 0, 0, 0));
        assertNull(service.approve(1L, "  ", null));
        verify(mapper, never()).updateById(any());
    }

    @Test
    public void reject_setsVeto_andCannotRejectTwice() {
        when(mapper.selectById(1L)).thenReturn(bill(1L, 1, 0, 0, 0));
        TSaltRenewBill r = service.reject(1L, "否决人", null);
        assertEquals(2, r.getStatus());

        when(mapper.selectById(1L)).thenReturn(bill(1L, 1, 0, 0, 2));
        assertNull(service.reject(1L, "再否一次", null));
    }

    @Test
    public void rollback_oneNodeOnly_resetsSignCount() {
        when(mapper.selectById(1L)).thenReturn(bill(1L, 2, 1, 2, 0));
        TSaltRenewBill r = service.rollback(1L, null);
        assertEquals(1, r.getNodeNo()); // 只退一档
        assertEquals(0, r.getSignCount());
        assertEquals(0, r.getStatus());
    }

    @Test
    public void rollback_atFirstNode_rejected() {
        when(mapper.selectById(1L)).thenReturn(bill(1L, 0, 0, 0, 0));
        assertNull(service.rollback(1L, null));
        verify(mapper, never()).updateById(any());
    }
}
