package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fc.v2.mapper.auto.TSaltEntMapper;
import com.fc.v2.mapper.auto.TSaltSpotRowMapper;
import com.fc.v2.model.auto.TSaltEnt;
import com.fc.v2.model.auto.TSaltSpotRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 抽检整批提交：企业代号对总名录档案、批次数目幂等、超上限整批不入库、
 * 非法行只记失败明细（不随全批重复落库）、行号从1连续、数目折两位。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class TSaltSpotRowServiceImplTest {

    @Mock
    private TSaltSpotRowMapper rowMapper;
    @Mock
    private TSaltEntMapper entMapper;

    private TSaltSpotRowServiceImpl service;

    @BeforeEach
    public void setUp() {
        service = new TSaltSpotRowServiceImpl();
        ReflectionTestUtils.setField(service, "saltSpotRowMapper", rowMapper);
        ReflectionTestUtils.setField(service, "saltEntMapper", entMapper);
        TSaltEnt ent = new TSaltEnt();
        ent.setSiteNo("JY00");
        when(entMapper.selectList(any())).thenReturn(new ArrayList<>(Arrays.asList(ent)));
        when(rowMapper.selectCount(any())).thenReturn(0);
        when(rowMapper.insert(any())).thenReturn(1);
    }

    private TSaltSpotRow row(String itemCode, String qty, String hours) {
        TSaltSpotRow r = new TSaltSpotRow();
        r.setItemCode(itemCode);
        r.setQty(new BigDecimal(qty));
        if (hours != null) {
            r.setHoursNum(new BigDecimal(hours));
        }
        return r;
    }

    @Test
    public void validBatch_insertsAndRoundsTwoDigits() {
        int n = service.submitBatch("B1", Arrays.asList(row("JY00", "3.456", "10")));
        assertEquals(1, n);
        org.mockito.ArgumentCaptor<TSaltSpotRow> cap =
                org.mockito.ArgumentCaptor.forClass(TSaltSpotRow.class);
        verify(rowMapper).insert(cap.capture());
        assertEquals(new BigDecimal("3.46"), cap.getValue().getQty());
        assertEquals(1, cap.getValue().getStatus());
        assertEquals(1, cap.getValue().getRowNo());
    }

    @Test
    public void unknownEntCode_rejectedToErrors_notAccepted() {
        // 第二行企业代号对不上档案，只有第一行收下；第二行落失败明细
        when(entMapper.selectList(any())).thenReturn(new ArrayList<>(Arrays.asList(ent("JY00"))));
        List<TSaltSpotRow> rows = Arrays.asList(row("JY00", "1", "2"), row("JYXX", "1", "2"));
        int n = service.submitBatch("B2", rows);
        assertEquals(1, n);
        org.mockito.ArgumentCaptor<TSaltSpotRow> cap =
                org.mockito.ArgumentCaptor.forClass(TSaltSpotRow.class);
        verify(rowMapper, times(2)).insert(cap.capture());
        boolean sawFail = false;
        for (TSaltSpotRow inserted : cap.getAllValues()) {
            if ("JYXX".equals(inserted.getItemCode())) {
                sawFail = true;
                assertEquals(2, inserted.getStatus()); // 已退回（失败明细）
                assertEquals(2, inserted.getRowNo());
            }
        }
        assertTrue(sawFail);
    }

    @Test
    public void repeatSubmit_sameBatch_isIdempotent() {
        when(rowMapper.selectCount(any())).thenReturn(3);
        int n = service.submitBatch("B3", Arrays.asList(row("JY00", "1", "1")));
        assertEquals(3, n); // 回的是已收行数，没有再 insert
        verify(rowMapper, org.mockito.Mockito.never()).insert(any());
    }

    @Test
    public void overLimit_nothingPersisted() {
        List<TSaltSpotRow> big = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            big.add(row("JY00", "1", "1"));
        }
        assertEquals(-1, service.submitBatch("B4", big));
        verify(rowMapper, org.mockito.Mockito.never()).insert(any());
    }

    @Test
    public void emptyAndNullBatch_returnZero() {
        assertEquals(0, service.submitBatch("B5", new ArrayList<TSaltSpotRow>()));
        assertEquals(0, service.submitBatch(null, Arrays.asList(row("JY00", "1", "1"))));
    }

    private TSaltEnt ent(String no) {
        TSaltEnt e = new TSaltEnt();
        e.setSiteNo(no);
        return e;
    }
}
