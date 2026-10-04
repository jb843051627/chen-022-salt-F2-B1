package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fc.v2.mapper.auto.TSaltEntMapper;
import com.fc.v2.mapper.auto.TSaltLinkPageMapper;
import com.fc.v2.model.auto.TSaltEnt;
import com.fc.v2.model.auto.TSaltLinkPage;
import com.fc.v2.model.custom.SaltBoardQuery;
import com.fc.v2.model.custom.SaltBoardResult;
import com.fc.v2.service.ITSaltLinkPageService.TSysUserView;
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
 * 挂接簿页服务层单测：校验拦截、欠挂重算、两位小数口径、两处码子系统发/人手填拒收、
 * 名录页属地圈定与三栏同算、摘牌旧页在册、起页署名钉死开单人。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class TSaltLinkPageServiceImplTest {

    @Mock
    private TSaltLinkPageMapper pageMapper;
    @Mock
    private TSaltEntMapper entMapper;

    private TSaltLinkPageServiceImpl service;

    private TSaltEnt activeEnt;

    @BeforeEach
    public void setUp() {
        service = new TSaltLinkPageServiceImpl(entMapper);
        ReflectionTestUtils.setField(service, "baseMapper", pageMapper);

        activeEnt = new TSaltEnt();
        activeEnt.setId(0L);
        activeEnt.setSiteNo("JY00");
        activeEnt.setSiteName("盐泽制盐一厂");
        activeEnt.setSiteType("制盐企业");
        activeEnt.setRoadName("盐岭省—盐泽市—临卤县");
        activeEnt.setStatus(0);
        when(entMapper.selectOne(any())).thenReturn(activeEnt);
        when(entMapper.selectList(any())).thenReturn(new ArrayList<TSaltEnt>());
        when(pageMapper.selectCount(any())).thenReturn(0);
        when(pageMapper.updateById(any())).thenReturn(1);
    }

    // ---------- 欠挂折数（两位小数口径）：64-41=23；25-9=16；48-48=0 ----------

    @Test
    public void lackCount_isShouldMinusDone() {
        assertLack("64", "41", "23.00");
        assertLack("25", "9", "16.00");
        assertLack("48", "48", "0.00");
    }

    // ---------- 五位照收折两位：列表/导出/库存同一口径 ----------

    @Test
    public void counts_roundToArchiveScale_twoDigits_halfUp() {
        TSaltLinkPage saved = openPage("10.235", "3.004", 2);
        assertEquals(new BigDecimal("10.24"), saved.getShouldCount());
        assertEquals(new BigDecimal("3.00"), saved.getDoneCount());
        assertEquals(new BigDecimal("7.24"), saved.getLackCount());
        // 折完仍是两位，屏上与导出拿到的都是这两个值
        assertEquals(2, saved.getShouldCount().scale());
        assertEquals(2, saved.getLackCount().scale());
    }

    private void assertLack(String should, String done, String expectLack) {
        TSaltLinkPage saved = openPage(should, done, 2);
        assertEquals(0, new BigDecimal(expectLack).compareTo(saved.getLackCount()),
                "应挂" + should + "已挂" + done + "，欠挂应折出" + expectLack);
    }

    // ---------- 两处码子：系统发；人手递值当场拒收，不静默读没 ----------

    @Test
    public void billNoAndSignCode_issuedBySystem_onCleanSubmit() {
        TSaltLinkPage r = new TSaltLinkPage();
        r.setSiteId(0);
        r.setNodeNo(2);
        r.setShouldCount(new BigDecimal("10"));
        r.setDoneCount(new BigDecimal("3"));

        TSaltLinkPage saved = service.openLinkPage(r);

        assertTrue(saved.getBillNo().startsWith("GJ"));
        assertTrue(saved.getSignCode().startsWith("QR"));
        assertEquals(0, new BigDecimal("7").compareTo(saved.getLackCount()));
        assertEquals(0, saved.getStatus());
        assertEquals("盐岭省—盐泽市—临卤县", saved.getScopeRoad());
    }

    @Test
    public void handedBillNo_isRejected_notSilentlyOverwritten() {
        TSaltLinkPage r = new TSaltLinkPage();
        r.setSiteId(0);
        r.setNodeNo(2);
        r.setShouldCount(new BigDecimal("10"));
        r.setDoneCount(new BigDecimal("3"));
        r.setBillNo("手写一个代号");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.openLinkPage(r));
        assertTrue(e.getMessage().contains("挂接代号"), e.getMessage());
        verify(pageMapper, never()).insert(any());
    }

    @Test
    public void handedSignCode_isRejected() {
        TSaltLinkPage r = new TSaltLinkPage();
        r.setSiteId(0);
        r.setNodeNo(2);
        r.setShouldCount(new BigDecimal("10"));
        r.setSignCode("手写一个签认码");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.openLinkPage(r));
        assertTrue(e.getMessage().contains("随行签认码"), e.getMessage());
        verify(pageMapper, never()).insert(any());
    }

    @Test
    public void handedLackCount_neverStored() {
        TSaltLinkPage r = new TSaltLinkPage();
        r.setSiteId(0);
        r.setNodeNo(2);
        r.setShouldCount(new BigDecimal("10"));
        r.setDoneCount(new BigDecimal("3"));
        r.setLackCount(new BigDecimal("999")); // 人报的欠挂不认

        TSaltLinkPage saved = service.openLinkPage(r);
        assertEquals(0, new BigDecimal("7").compareTo(saved.getLackCount()));
    }

    // ---------- 拦得住：空、负、应挂低于已挂 ----------

    @Test
    public void shouldBlank_blocked() {
        TSaltLinkPage r = new TSaltLinkPage();
        r.setSiteId(0);
        r.setNodeNo(2);
        r.setDoneCount(new BigDecimal("1"));
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> service.openLinkPage(r));
        assertTrue(e.getMessage().contains("应挂品种数"), e.getMessage());
        verify(pageMapper, never()).insert(any());
    }

    @Test
    public void negativeCounts_blocked() {
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> openPage("-1", "0", 2));
        assertTrue(e1.getMessage().contains("应挂品种数"));

        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> openPage("10", "-2", 2));
        assertTrue(e2.getMessage().contains("已挂品种数"));
    }

    @Test
    public void doneOverShould_blocked_andNamesBothColumns() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> openPage("9", "10", 2));
        assertTrue(e.getMessage().contains("已挂品种数"), e.getMessage());
        assertTrue(e.getMessage().contains("应挂品种数"), e.getMessage());
        verify(pageMapper, never()).insert(any());
    }

    @Test
    public void missingEnt_blocked() {
        when(entMapper.selectOne(any())).thenReturn(null);
        TSaltLinkPage r = new TSaltLinkPage();
        r.setSiteId(999);
        r.setNodeNo(2);
        r.setShouldCount(new BigDecimal("5"));
        assertThrows(IllegalArgumentException.class, () -> service.openLinkPage(r));
    }

    // ---------- 摘牌企业不开新页；旧页照旧查得着、算在册、能改 ----------

    @Test
    public void delistedEnt_cannotOpenNewPage() {
        activeEnt.setStatus(1);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> openPage("10", "0", 2));
        assertTrue(e.getMessage().contains("摘牌"));
        verify(pageMapper, never()).insert(any());
    }

    @Test
    public void delistedEnt_oldPageStillEditable() {
        activeEnt.setStatus(1); // 起页之后才摘的牌
        TSaltLinkPage old = persistedPage(100L, "GJ1", "QR1", 10, 4, 6, 2,
                "盐岭省—盐泽市—临卤县", 0);
        when(pageMapper.selectById(100L)).thenReturn(old);

        TSaltLinkPage form = new TSaltLinkPage();
        form.setId(100L);
        form.setDoneCount(new BigDecimal("9")); // 已挂数一变
        service.resaveLinkPage(form);
        TSaltLinkPage saved = captureUpdated();

        assertEquals(0, new BigDecimal("1").compareTo(saved.getLackCount()));
        assertEquals("GJ1", saved.getBillNo()); // 代号不换手
        verify(pageMapper).updateById(any());
    }

    // ---------- 三个场合重算：换挂靠层 ----------

    @Test
    public void changeNode_recomputesRoadAndLack() {
        TSaltLinkPage old = persistedPage(101L, "GJ2", "QR2", 20, 20, 0, 2,
                "盐岭省—盐泽市—临卤县", 1);
        when(pageMapper.selectById(101L)).thenReturn(old);

        TSaltLinkPage form = new TSaltLinkPage();
        form.setId(101L);
        form.setNodeNo(1); // 改压到市一层
        service.resaveLinkPage(form);
        TSaltLinkPage saved = captureUpdated();

        assertEquals("盐岭省—盐泽市", saved.getScopeRoad());
        assertEquals(1, saved.getNodeNo());
        assertEquals(0, new BigDecimal("0").compareTo(saved.getLackCount()));
        assertEquals("GJ2", saved.getBillNo());
        assertEquals("QR2", saved.getSignCode());
    }

    @Test
    public void resave_changedBillNo_isRejected() {
        TSaltLinkPage old = persistedPage(102L, "GJ3", "QR3", 30, 10, 20, 2,
                "盐岭省—盐泽市—临卤县", 0);
        when(pageMapper.selectById(102L)).thenReturn(old);

        TSaltLinkPage form = new TSaltLinkPage();
        form.setId(102L);
        form.setDoneCount(new BigDecimal("25"));
        form.setBillNo("HACK"); // 人手改了代号
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.resaveLinkPage(form));
        assertTrue(e.getMessage().contains("挂接代号"), e.getMessage());
        verify(pageMapper, never()).updateById(any());
    }

    @Test
    public void resave_sameBillNo_unchangedSaveAllowed() {
        // 编辑场景：代号原样不动，只改数目——保存必须放行，不被自己的唯一校验绊住
        TSaltLinkPage old = persistedPage(103L, "GJ4", "QR4", 30, 10, 20, 2,
                "盐岭省—盐泽市—临卤县", 0);
        when(pageMapper.selectById(103L)).thenReturn(old);

        TSaltLinkPage form = new TSaltLinkPage();
        form.setId(103L);
        form.setBillNo("GJ4"); // 原样回传
        form.setShouldCount(new BigDecimal("30"));
        form.setDoneCount(new BigDecimal("12"));
        service.resaveLinkPage(form);
        TSaltLinkPage saved = captureUpdated();

        assertEquals("GJ4", saved.getBillNo());
        assertEquals(0, new BigDecimal("18").compareTo(saved.getLackCount()));
    }

    // ---------- 起页署名：钉死成实际开单人，不认表单交来的值 ----------

    @Test
    public void openPage_operatorName_pinnedAsCreator() {
        TSaltLinkPage r = new TSaltLinkPage();
        r.setSiteId(0);
        r.setNodeNo(2);
        r.setShouldCount(new BigDecimal("10"));
        r.setCreateBy("表单上别人填的名字");

        TSaltLinkPage saved = service.openLinkPage(r, "开单人王二");

        assertEquals("开单人王二", saved.getCreateBy());
    }

    @Test
    public void resave_creatorStaysFirstOperator() {
        TSaltLinkPage old = persistedPage(104L, "GJ5", "QR5", 10, 2, 8, 2,
                "盐岭省—盐泽市—临卤县", 0);
        old.setCreateBy("头回开单人");
        when(pageMapper.selectById(104L)).thenReturn(old);

        TSaltLinkPage form = new TSaltLinkPage();
        form.setId(104L);
        form.setDoneCount(new BigDecimal("5"));
        service.resaveLinkPage(form, "后来重挂人");
        TSaltLinkPage saved = captureUpdated();

        // 更新不碰 create_by：库里的"头回开单人"原样留着；重挂人只进 update_by
        assertNull(saved.getCreateBy());
        assertEquals("后来重挂人", saved.getUpdateBy());
    }

    // ---------- 名录页一屏：属地圈定 + 三栏同算 ----------

    @Test
    public void board_countsComeFromSameScoop_asRows() {
        List<TSaltLinkPage> scoop = Arrays.asList(
                page("GJ-a", 10, 10, 0, 1, "盐岭省—盐泽市—临卤县"), // 已挂讫
                page("GJ-b", 10, 4, 6, 0, "盐岭省—盐泽市—临卤县"),  // 待挂
                page("GJ-c", 10, 10, 0, 2, "盐岭省—盐泽市—临卤县"), // 压页
                page("GJ-d", 8, 3, 5, 1, "盐岭省—盐泽市—临卤县"));  // 已挂讫
        when(pageMapper.selectList(any())).thenReturn(new ArrayList<>(scoop));

        SaltBoardQuery q = new SaltBoardQuery();
        q.setPage(1);
        q.setLimit(10);
        SaltBoardResult board = service.openBoard(q,
                new TSysUserView("盐岭省—盐泽市—临卤县", 2));

        assertEquals(4, board.getRegisteredCount());
        assertEquals(2, board.getDoneCount());
        assertEquals(1, board.getHeldCount());
        assertEquals(4, board.getTotal());
        assertEquals(4, board.getRows().size());
    }

    @Test
    public void board_statusFilterGoesIntoQuery() {
        when(pageMapper.selectList(any())).thenReturn(new ArrayList<TSaltLinkPage>());

        SaltBoardQuery q = new SaltBoardQuery();
        q.setStatus(1);
        service.openBoard(q, new TSysUserView("盐岭省—盐泽市—临卤县", 2));

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TSaltLinkPage>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(pageMapper).selectList(cap.capture());
        String sql = cap.getValue().getTargetSql().toLowerCase();
        assertTrue(sql.contains("status = ?"), sql);
        assertTrue(cap.getValue().getParamNameValuePairs().containsValue(1));
    }

    @Test
    public void board_scopeFilter_pinsRoadAndLevel() {
        when(pageMapper.selectList(any())).thenReturn(new ArrayList<TSaltLinkPage>());

        SaltBoardQuery q = new SaltBoardQuery();
        service.openBoard(q, new TSysUserView("盐岭省—盐泽市—青卤县", 2));

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TSaltLinkPage>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(pageMapper).selectList(cap.capture());
        String sql = cap.getValue().getTargetSql().toLowerCase();
        // 县里翻不出邻县的页、市里不夹别市的行：路与层都钉死在本层本路
        assertTrue(sql.contains("scope_road = ?"), sql);
        assertTrue(sql.contains("node_no = ?"), sql);
        assertTrue(cap.getValue().getParamNameValuePairs().containsValue("盐岭省—盐泽市—青卤县"));
        assertTrue(cap.getValue().getParamNameValuePairs().containsValue(2));
    }

    @Test
    public void board_emptyWhenNoPagesThisSeason() {
        when(pageMapper.selectList(any())).thenReturn(new ArrayList<TSaltLinkPage>());
        SaltBoardResult board = service.openBoard(new SaltBoardQuery(),
                new TSysUserView("盐岭省—盐泽市—临卤县", 2));
        // 本层没起过页，那一屏空着，三栏归零，不拿旁处的行凑数
        assertEquals(0, board.getRegisteredCount());
        assertEquals(0, board.getDoneCount());
        assertEquals(0, board.getHeldCount());
        assertTrue(board.getRows().isEmpty());
    }

    @Test
    public void board_paginationSlicesScoop() {
        List<TSaltLinkPage> scoop = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            scoop.add(page("GJ-" + i, 10, 1, 9, 0, "盐岭省—盐泽市—临卤县"));
        }
        when(pageMapper.selectList(any())).thenReturn(scoop);

        SaltBoardQuery q = new SaltBoardQuery();
        q.setPage(2);
        q.setLimit(10);
        SaltBoardResult board = service.openBoard(q,
                new TSysUserView("盐岭省—盐泽市—临卤县", 2));

        assertEquals(25, board.getRegisteredCount()); // 三栏按整批算
        assertEquals(10, board.getRows().size());     // 屏上只摆第二屏十条
    }

    @Test
    public void scoopBoard_andBoard_shareSameRows() {
        // 列表与导出同一只勺：同筛选、同口径，数目对得上
        List<TSaltLinkPage> scoop = Arrays.asList(
                page("GJ-x", 12, 5, 7, 0, "盐岭省—盐泽市—临卤县"));
        when(pageMapper.selectList(any())).thenReturn(new ArrayList<>(scoop));

        SaltBoardQuery q = new SaltBoardQuery();
        SaltBoardResult board = service.openBoard(q, new TSysUserView("盐岭省—盐泽市—临卤县", 2));
        List<TSaltLinkPage> exported = service.scoopBoard(q, new TSysUserView("盐岭省—盐泽市—临卤县", 2));

        assertEquals(board.getTotal(), exported.size());
        assertEquals(board.getRows().get(0).getLackCount(), exported.get(0).getLackCount());
    }

    // ---------- 进展推进/退回守边界，签认码不清 ----------

    @Test
    public void advance_nullStatusGoesPendingToDone_noNpe() {
        TSaltLinkPage old = persistedPage(200L, "GJ9", null, 5, 1, 4, 2,
                "盐岭省—盐泽市—临卤县", null);
        when(pageMapper.selectById(200L)).thenReturn(old);
        assertEquals(1, service.advanceTSaltLinkPage(200L));
        ArgumentCaptor<TSaltLinkPage> cap = ArgumentCaptor.forClass(TSaltLinkPage.class);
        verify(pageMapper).updateById(cap.capture());
        assertEquals(1, cap.getValue().getStatus());
        assertTrue(cap.getValue().getSignCode().startsWith("QR")); // 缺位补发
    }

    @Test
    public void advance_atHeld_cannotJump() {
        TSaltLinkPage old = persistedPage(201L, "GJ10", "QR10", 5, 5, 0, 2,
                "盐岭省—盐泽市—临卤县", 2);
        when(pageMapper.selectById(201L)).thenReturn(old);
        assertEquals(0, service.advanceTSaltLinkPage(201L));
        verify(pageMapper, never()).updateById(any());
    }

    @Test
    public void revert_keepsSignCode_andStaysInRegister() {
        TSaltLinkPage old = persistedPage(202L, "GJ11", "QR11", 5, 5, 0, 2,
                "盐岭省—盐泽市—临卤县", 1);
        when(pageMapper.selectById(202L)).thenReturn(old);
        assertEquals(1, service.revertTSaltLinkPage(202L));
        ArgumentCaptor<TSaltLinkPage> cap = ArgumentCaptor.forClass(TSaltLinkPage.class);
        verify(pageMapper).updateById(cap.capture());
        assertEquals(0, cap.getValue().getStatus());
        // 部分更新不带签认码、不带删除标记：库里的 QR11 不动，旧页照旧在册
        assertNull(cap.getValue().getSignCode());
        assertNull(cap.getValue().getDelFlag());
    }

    // ---------- 企业总名录状态变更 ----------

    @Test
    public void entStatus_change_dlistedBlocksNewPage() {
        when(entMapper.selectOne(any())).thenReturn(activeEnt);
        service.changeEntRegisterStatus(0L, 1, "经办人");
        ArgumentCaptor<TSaltEnt> cap = ArgumentCaptor.forClass(TSaltEnt.class);
        verify(entMapper).updateById(cap.capture());
        assertEquals(1, cap.getValue().getStatus().intValue());
        assertEquals("经办人", cap.getValue().getUpdateBy());
    }

    @Test
    public void entStatus_invalidValue_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.changeEntRegisterStatus(0L, 9, "经办人"));
        verify(entMapper, never()).updateById(any());
    }

    // ---------- 企业总名录类别排序 ----------

    @Test
    public void entRegister_orderByFixedTypeOrder() {
        when(entMapper.selectList(any())).thenReturn(new ArrayList<TSaltEnt>());
        service.listEntRegister(null);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TSaltEnt>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(entMapper).selectList(cap.capture());
        String sql = cap.getValue().getTargetSql();
        assertTrue(sql.contains("制盐企业"));
        assertTrue(sql.indexOf("制盐企业") < sql.indexOf("省内批发企业"));
        assertTrue(sql.indexOf("省内批发企业") < sql.indexOf("跨省批发企业"));
    }

    // ---------- helpers ----------

    private TSaltLinkPage captureUpdated() {
        ArgumentCaptor<TSaltLinkPage> cap = ArgumentCaptor.forClass(TSaltLinkPage.class);
        verify(pageMapper).updateById(cap.capture());
        return cap.getValue();
    }

    private TSaltLinkPage openPage(String should, String done, int node) {
        TSaltLinkPage r = new TSaltLinkPage();
        r.setSiteId(0);
        r.setNodeNo(node);
        if (should != null) {
            r.setShouldCount(new BigDecimal(should));
        }
        if (done != null) {
            r.setDoneCount(new BigDecimal(done));
        }
        return service.openLinkPage(r);
    }

    private TSaltLinkPage persistedPage(Long id, String billNo, String signCode,
                                        int should, int done, int lack, int node,
                                        String road, Integer status) {
        TSaltLinkPage p = new TSaltLinkPage();
        p.setId(id);
        p.setBillNo(billNo);
        p.setSignCode(signCode);
        p.setSiteId(0);
        p.setSiteNo("JY00");
        p.setShouldCount(BigDecimal.valueOf(should));
        p.setDoneCount(BigDecimal.valueOf(done));
        p.setLackCount(BigDecimal.valueOf(lack));
        p.setNodeNo(node);
        p.setScopeRoad(road);
        p.setStatus(status);
        p.setDelFlag(0);
        return p;
    }

    private TSaltLinkPage page(String billNo, int should, int done, int lack, int status, String road) {
        return persistedPage((long) billNo.hashCode(), billNo, "QR" + billNo,
                should, done, lack, 2, road, status);
    }
}
