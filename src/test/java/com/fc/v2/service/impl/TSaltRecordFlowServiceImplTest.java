package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fc.v2.mapper.auto.TSaltRecordFlowMapper;
import com.fc.v2.model.auto.TSaltRecordFlow;
import com.fc.v2.service.ITSaltLinkPageService;
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
 * 备案单流转：逐档推进（不跳档）、逐档退回、封卷置态、
 * 封卷/挪回两个口子与挂接簿联动（已挂 ±1），登单代号对档案且不许重登。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class TSaltRecordFlowServiceImplTest {

    @Mock
    private TSaltRecordFlowMapper flowMapper;
    @Mock
    private ITSaltLinkPageService linkPageService;

    private TSaltRecordFlowServiceImpl service;

    @BeforeEach
    public void setUp() {
        service = new TSaltRecordFlowServiceImpl(linkPageService);
        ReflectionTestUtils.setField(service, "saltRecordFlowMapper", flowMapper);
        when(flowMapper.updateById(any())).thenReturn(1);
    }

    private TSaltRecordFlow flow(Long id, String bizNo, int stage, int status) {
        TSaltRecordFlow r = new TSaltRecordFlow();
        r.setId(id);
        r.setBizNo(bizNo);
        r.setStage(stage);
        r.setStatus(status);
        r.setDelFlag(0);
        return r;
    }

    @Test
    public void advance_oneStageAtATime_noLeap() {
        when(flowMapper.selectById(1L)).thenReturn(flow(1L, "GJ1", 0, 0));
        TSaltRecordFlow out = service.advance(1L, null);
        ArgumentCaptor<TSaltRecordFlow> cap = ArgumentCaptor.forClass(TSaltRecordFlow.class);
        verify(flowMapper).updateById(cap.capture());
        assertEquals(1, cap.getValue().getStage().intValue(), "一次只许挪一档，不许 +2 跳档");
        assertEquals(1, cap.getValue().getStatus().intValue());
    }

    @Test
    public void advance_toTop_setsTerminal_andLinksDone() {
        when(flowMapper.selectById(2L)).thenReturn(flow(2L, "GJ2", 2, 1));
        service.advance(2L, null);
        ArgumentCaptor<TSaltRecordFlow> cap = ArgumentCaptor.forClass(TSaltRecordFlow.class);
        verify(flowMapper).updateById(cap.capture());
        assertEquals(3, cap.getValue().getStage().intValue());
        assertEquals(2, cap.getValue().getStatus().intValue(), "到缴回封卷置已封卷");
        // 联动口子：封卷挪一格已挂
        verify(linkPageService).applyLinkDone("GJ2", 1);
    }

    @Test
    public void advance_atTerminal_rejected_noLink() {
        when(flowMapper.selectById(3L)).thenReturn(flow(3L, "GJ3", 3, 2));
        assertNull(service.advance(3L, null));
        verify(flowMapper, never()).updateById(any());
        verify(linkPageService, never()).applyLinkDone(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    public void rollback_oneStage_setsActive_andReversesLinkWhenLeavingTerminal() {
        when(flowMapper.selectById(4L)).thenReturn(flow(4L, "GJ4", 3, 2));
        service.rollback(4L, null);
        ArgumentCaptor<TSaltRecordFlow> cap = ArgumentCaptor.forClass(TSaltRecordFlow.class);
        verify(flowMapper).updateById(cap.capture());
        assertEquals(2, cap.getValue().getStage().intValue(), "退一档，不是直接归 0");
        assertEquals(1, cap.getValue().getStatus().intValue());
        verify(linkPageService).applyLinkDone("GJ4", -1);
    }

    @Test
    public void rollback_midStage_doesNotTouchLink() {
        when(flowMapper.selectById(5L)).thenReturn(flow(5L, "GJ5", 2, 1));
        service.rollback(5L, null);
        verify(linkPageService, never()).applyLinkDone(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    public void rollback_atFirstStage_rejected() {
        when(flowMapper.selectById(6L)).thenReturn(flow(6L, "GJ6", 0, 0));
        assertNull(service.rollback(6L, null));
        verify(flowMapper, never()).updateById(any());
    }

    @Test
    public void terminal_cannotChangeContentOrRemove() {
        when(flowMapper.selectById(7L)).thenReturn(flow(7L, "GJ7", 3, 2));
        assertEquals(false, service.updateContent(7L, "改定案卷宗"));
        assertEquals(false, service.remove(7L));
        verify(flowMapper, never()).updateById(any());
    }

    @Test
    public void remove_isSoftDelete() {
        when(flowMapper.selectById(8L)).thenReturn(flow(8L, "GJ8", 1, 1));
        assertTrue(service.remove(8L));
        ArgumentCaptor<TSaltRecordFlow> cap = ArgumentCaptor.forClass(TSaltRecordFlow.class);
        verify(flowMapper).updateById(cap.capture());
        assertEquals(1, cap.getValue().getDelFlag().intValue(), "沉底，不真抹");
    }

    @Test
    public void missingOrDeleted_treatedAsNull() {
        when(flowMapper.selectById(9L)).thenReturn(null);
        assertNull(service.advance(9L, null));
        TSaltRecordFlow gone = flow(10L, "GJ10", 1, 1);
        gone.setDelFlag(1);
        when(flowMapper.selectById(10L)).thenReturn(gone);
        assertNull(service.advance(10L, null));
    }

    // ---------- 登一笔：代号对档案、在册代号不许重登 ----------

    @Test
    public void register_billNoMustMatchArchive() {
        when(linkPageService.billNoInRegister("FAKE")).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> service.register("FAKE"));
        verify(flowMapper, never()).insert(any());
    }

    @Test
    public void register_duplicateActiveBlocked() {
        when(linkPageService.billNoInRegister("GJ1")).thenReturn(true);
        when(flowMapper.selectCount(any())).thenReturn(1); // 已有一笔在流转
        assertThrows(IllegalArgumentException.class, () -> service.register("GJ1"));
        verify(flowMapper, never()).insert(any());
    }

    @Test
    public void register_ok_startsAtStage0Unstarted() {
        when(linkPageService.billNoInRegister("GJ1")).thenReturn(true);
        when(flowMapper.selectCount(any())).thenReturn(0);
        service.register("GJ1");
        ArgumentCaptor<TSaltRecordFlow> cap = ArgumentCaptor.forClass(TSaltRecordFlow.class);
        verify(flowMapper).insert(cap.capture());
        assertEquals("GJ1", cap.getValue().getBizNo());
        assertEquals(0, cap.getValue().getStage().intValue());
        assertEquals(0, cap.getValue().getStatus().intValue());
    }
}
