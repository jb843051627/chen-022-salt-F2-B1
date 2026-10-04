package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fc.v2.mapper.auto.TSaltRecordFlowMapper;
import com.fc.v2.model.auto.TSaltRecordFlow;
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
 * 备案单状态机：逐档 +1（不再一次跳两档），缴回封卷置终态，
 * 回退逐档 -1 且头档不退；封卷后不许改、不许删。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class TSaltRecordFlowServiceImplTest {

    @Mock
    private TSaltRecordFlowMapper mapper;

    private TSaltRecordFlowServiceImpl service;

    @BeforeEach
    public void setUp() {
        service = new TSaltRecordFlowServiceImpl();
        ReflectionTestUtils.setField(service, "saltRecordFlowMapper", mapper);
        when(mapper.updateById(any())).thenReturn(1);
    }

    private TSaltRecordFlow bill(Long id, Integer stage, Integer status) {
        TSaltRecordFlow r = new TSaltRecordFlow();
        r.setId(id);
        r.setStage(stage);
        r.setStatus(status);
        r.setDelFlag(0);
        return r;
    }

    @Test
    public void advance_movesOneStageAtATime_notTwo() {
        when(mapper.selectById(1L)).thenReturn(bill(1L, 0, 0));
        TSaltRecordFlow r = service.advance(1L, null);
        assertEquals(1, r.getStage());
        assertEquals(1, r.getStatus()); // 在办，不是封卷
    }

    @Test
    public void advance_toFinalStage_setsTerminal() {
        when(mapper.selectById(1L)).thenReturn(bill(1L, 2, 1));
        TSaltRecordFlow r = service.advance(1L, null);
        assertEquals(3, r.getStage());
        assertEquals(2, r.getStatus()); // 缴回封卷
    }

    @Test
    public void advance_atTerminal_rejected() {
        when(mapper.selectById(1L)).thenReturn(bill(1L, 3, 2));
        assertNull(service.advance(1L, null));
        verify(mapper, never()).updateById(any());
    }

    @Test
    public void rollback_movesOneStageBack_notResetToZero() {
        when(mapper.selectById(1L)).thenReturn(bill(1L, 3, 2));
        TSaltRecordFlow r = service.rollback(1L, null);
        assertEquals(2, r.getStage()); // 只退一档，不一把归零
        assertEquals(1, r.getStatus());
    }

    @Test
    public void rollback_atFirstStage_rejected() {
        when(mapper.selectById(1L)).thenReturn(bill(1L, 0, 1));
        assertNull(service.rollback(1L, null));
        verify(mapper, never()).updateById(any());
    }

    @Test
    public void terminal_cannotUpdateOrRemove() {
        when(mapper.selectById(1L)).thenReturn(bill(1L, 3, 2));
        assertFalse(service.updateContent(1L, "改"));
        assertFalse(service.remove(1L));
        verify(mapper, never()).deleteById(any());
    }

    @Test
    public void deletedBill_isInvisible() {
        TSaltRecordFlow dead = bill(1L, 1, 1);
        dead.setDelFlag(1);
        when(mapper.selectById(1L)).thenReturn(dead);
        assertNull(service.advance(1L, null));
    }
}
