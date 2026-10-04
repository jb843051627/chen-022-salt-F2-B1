package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
 * 挂接簿页服务层单测：校验拦截、欠挂重算、两处码子系统发、
 * 名录页属地圈定与三栏同算、摘牌旧页在册。
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

    // ---------- 欠挂折数：应挂64已挂41→23；25/9→16；48/48→0 ----------

    @Test
    public void lackCount_isShouldMinusDone() {
        assertLack(64, 41, 23);
        assertLack(25, 9, 16);
        assertLack(48, 48, 0);
    }

    private void assertLack(int should, int done, int expectLack) {
        TSaltLinkPage saved = openPage(should, done, 2);
        assertEquals(expectLack, saved.getLackCount(),
                "应挂" + should + "已挂" + done + "，欠挂应折出" + expectLack);
    }

    // ---------- 两处码子归系统发，人手递上来不收 ----------

    @Test
    public void billNoAndSignCode_issuedBySystem_handValuesRejected() {
        TSaltLinkPage r = new TSaltLinkPage();
        r.setSiteId(0);
        r.setNodeNo(2);
        r.setShouldCount(10);
        r.setDoneCount(3);
        r.setBillNo("手写一个代号");
        r.setSignCode("手写一个签认码");
        r.setLackCount(999); // 人报的欠挂也不认

        TSaltLinkPage saved = service.openLinkPage(r);

        assertNotEquals("手写一个代号", saved.getBillNo());
        assertNotEquals("手写一个签认码", saved.getSignCode());
        assertTrue(saved.getBillNo().startsWith("GJ"));
        assertTrue(saved.getSignCode().startsWith("QR"));
        assertEquals(7, saved.getLackCount());
        assertEquals(0, saved.getStatus());
        assertEquals("盐岭省—盐泽市—临卤县", saved.getScopeRoad());
    }

    // ---------- 拦得住：空、负、应挂低于已挂 ----------

    @Test
    public void shouldBlank_blocked() {
        TSaltLinkPage r = new TSaltLinkPage();
        r.setSiteId(0);
        r.setNodeNo(2);
        r.setDoneCount(1);
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> service.openLinkPage(r));
        assertTrue(e.getMessage().contains("应挂品种数"), e.getMessage());
        verify(pageMapper, never()).insert(any());
    }

    @Test
    public void negativeCounts_blocked() {
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> openPage(-1, 0, 2));
        assertTrue(e1.getMessage().contains("应挂品种数"));

        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> openPage(10, -2, 2));
        assertTrue(e2.getMessage().contains("已挂品种数"));
    }

    @Test
    public void doneOverShould_blocked_andNamesBothColumns() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> openPage(9, 10, 2));
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
        r.setShouldCount(5);
        assertThrows(IllegalArgumentException.class, () -> service.openLinkPage(r));
    }

    // ---------- 摘牌企业不开新页；旧页照旧查得着、算在册、能改 ----------

    @Test
    public void delistedEnt_cannotOpenNewPage() {
        activeEnt.setStatus(1);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> openPage(10, 0, 2));
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
        form.setDoneCount(9); // 已挂数一变
        service.resaveLinkPage(form);
        TSaltLinkPage saved = captureUpdated();

        assertEquals(1, saved.getLackCount());
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
        assertEquals(0, saved.getLackCount());
        assertEquals("GJ2", saved.getBillNo());
        assertEquals("QR2", saved.getSignCode());
    }

    @Test
    public void resave_ignoresHandedLackAndBillNo() {
        TSaltLinkPage old = persistedPage(102L, "GJ3", "QR3", 30, 10, 20, 2,
                "盐岭省—盐泽市—临卤县", 0);
        when(pageMapper.selectById(102L)).thenReturn(old);

        TSaltLinkPage form = new TSaltLinkPage();
        form.setId(102L);
        form.setDoneCount(25);
        form.setLackCount(1);          // 人手塞的欠挂
        form.setBillNo("HACK");        // 人手塞的代号
        service.resaveLinkPage(form);
        TSaltLinkPage saved = captureUpdated();

        assertEquals(5, saved.getLackCount());
        assertEquals("GJ3", saved.getBillNo());
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
    public void board_pageSizeChange_isHonored() {
        List<TSaltLinkPage> scoop = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            scoop.add(page("GJ-S" + i, 10, 1, 9, 0, "盐岭省—盐泽市—临卤县"));
        }
        when(pageMapper.selectList(any())).thenReturn(scoop);

        SaltBoardQuery q = new SaltBoardQuery();
        q.setPage(1);
        q.setLimit(20); // 界面上把每页条数改成 20
        SaltBoardResult board = service.openBoard(q,
                new TSysUserView("盐岭省—盐泽市—临卤县", 2));

        assertEquals(20, board.getRows().size(), "接口必须按新条数吐 20 行，不能照旧 10 行");
        assertEquals(25, board.getTotal());
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

    // ---------- 名录页进展筛选 ----------

    @Test
    public void board_statusFilter_pinsStatus() {
        when(pageMapper.selectList(any())).thenReturn(new ArrayList<TSaltLinkPage>());

        SaltBoardQuery q = new SaltBoardQuery();
        q.setStatus(1); // 只看已挂讫
        service.openBoard(q, new TSysUserView("盐岭省—盐泽市—临卤县", 2));

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TSaltLinkPage>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(pageMapper).selectList(cap.capture());
        String sql = cap.getValue().getTargetSql().toLowerCase();
        assertTrue(sql.contains("status = ?"), sql);
        assertTrue(cap.getValue().getParamNameValuePairs().containsValue(1));
    }

    @Test
    public void board_statusFilter_illegalValueIgnored() {
        when(pageMapper.selectList(any())).thenReturn(new ArrayList<TSaltLinkPage>());
        SaltBoardQuery q = new SaltBoardQuery();
        q.setStatus(9); // 越界值不当筛选条件
        service.openBoard(q, new TSysUserView("盐岭省—盐泽市—临卤县", 2));
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TSaltLinkPage>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(pageMapper).selectList(cap.capture());
        assertFalse(cap.getValue().getTargetSql().toLowerCase().contains("status = ?"));
    }

    // ---------- 列表与导出同一勺 ----------

    @Test
    public void export_sharesScoopWithBoard() {
        when(pageMapper.selectList(any())).thenReturn(new ArrayList<TSaltLinkPage>());

        SaltBoardQuery q = new SaltBoardQuery();
        q.setBillNo("GJ-9");
        q.setStatus(2);
        TSysUserView op = new TSysUserView("盐岭省—盐泽市—临卤县", 2);

        service.openBoard(q, op);
        service.listBoardRows(q, op);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<TSaltLinkPage>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(pageMapper, org.mockito.Mockito.times(2)).selectList(cap.capture());
        String listSql = cap.getAllValues().get(0).getTargetSql();
        String exportSql = cap.getAllValues().get(1).getTargetSql();
        // 列表与导出长出同一套条件，数目不会对不上
        assertEquals(listSql, exportSql);
    }

    // ---------- 名录停用/恢复 ----------

    @Test
    public void changeEntStatus_delist_thenResume() {
        when(entMapper.selectById(5L)).thenReturn(ent(5L, "JY5", 0));
        TSaltEnt e = service.changeEntStatus(5L, 1);
        assertEquals(1, e.getStatus().intValue());
        verify(entMapper).updateById(any());

        when(entMapper.selectById(5L)).thenReturn(ent(5L, "JY5", 1));
        assertEquals(0, service.changeEntStatus(5L, 0).getStatus().intValue());
    }

    @Test
    public void changeEntStatus_rejectsBadStatusAndMissing() {
        assertThrows(IllegalArgumentException.class, () -> service.changeEntStatus(5L, 9));
        when(entMapper.selectById(6L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.changeEntStatus(6L, 1));
    }

    // ---------- 跨模块联动：只动已挂，不碰应挂 ----------

    @Test
    public void applyLinkDone_movesDoneOnly_notShould() {
        TSaltLinkPage p = persistedPage(300L, "GJ20", "QR20", 10, 4, 6, 2,
                "盐岭省—盐泽市—临卤县", 0);
        when(pageMapper.selectOne(any())).thenReturn(p);
        when(pageMapper.selectById(300L)).thenReturn(p);

        TSaltLinkPage out = service.applyLinkDone("GJ20", 1);

        assertEquals(10, out.getShouldCount().intValue(), "领一次不应把应挂抬一格");
        ArgumentCaptor<TSaltLinkPage> cap = ArgumentCaptor.forClass(TSaltLinkPage.class);
        verify(pageMapper).updateById(cap.capture());
        assertEquals(5, cap.getValue().getDoneCount().intValue());
        assertEquals(5, cap.getValue().getLackCount().intValue());
        assertNull(cap.getValue().getShouldCount(), "应挂列不进更新语句");
    }

    @Test
    public void applyLinkDone_fullDone_setsStatusDone() {
        TSaltLinkPage p = persistedPage(301L, "GJ21", "QR21", 5, 4, 1, 2,
                "盐岭省—盐泽市—临卤县", 0);
        when(pageMapper.selectOne(any())).thenReturn(p);
        when(pageMapper.selectById(301L)).thenReturn(p);
        service.applyLinkDone("GJ21", 1);
        ArgumentCaptor<TSaltLinkPage> cap = ArgumentCaptor.forClass(TSaltLinkPage.class);
        verify(pageMapper).updateById(cap.capture());
        assertEquals(5, cap.getValue().getDoneCount().intValue());
        assertEquals(0, cap.getValue().getLackCount().intValue());
        assertEquals(1, cap.getValue().getStatus().intValue()); // 挂齐→已挂讫
    }

    @Test
    public void applyLinkDone_clampsBounds() {
        TSaltLinkPage full = persistedPage(302L, "GJ22", "QR22", 5, 5, 0, 2,
                "盐岭省—盐泽市—临卤县", 1);
        when(pageMapper.selectOne(any())).thenReturn(full);
        assertThrows(IllegalArgumentException.class, () -> service.applyLinkDone("GJ22", 1));

        TSaltLinkPage zero = persistedPage(303L, "GJ23", "QR23", 5, 0, 5, 2,
                "盐岭省—盐泽市—临卤县", 0);
        when(pageMapper.selectOne(any())).thenReturn(zero);
        assertThrows(IllegalArgumentException.class, () -> service.applyLinkDone("GJ23", -1));

        when(pageMapper.selectOne(any())).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> service.applyLinkDone("NOPE", 1));
        verify(pageMapper, never()).updateById(any());
    }

    @Test
    public void billNoInRegister_checksArchive() {
        when(pageMapper.selectCount(any())).thenReturn(1);
        assertTrue(service.billNoInRegister("GJ1"));
        when(pageMapper.selectCount(any())).thenReturn(0);
        assertFalse(service.billNoInRegister("FAKE"));
        assertFalse(service.billNoInRegister(""));
    }

    // ---------- 起页署名：表单塞 createBy 不认，落登录人 ----------

    @Test
    public void openLinkPage_handedCreateBy_cleared() {
        TSaltLinkPage r = new TSaltLinkPage();
        r.setSiteId(0);
        r.setNodeNo(2);
        r.setShouldCount(10);
        r.setDoneCount(3);
        r.setCreateBy("表单里塞进来的别人");

        service.openLinkPage(r);

        ArgumentCaptor<TSaltLinkPage> cap = ArgumentCaptor.forClass(TSaltLinkPage.class);
        verify(pageMapper).insert(cap.capture());
        assertNull(cap.getValue().getCreateBy(), "开页人归登录人，人手塞的值抹掉交填充器");
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

    private TSaltLinkPage openPage(int should, int done, int node) {
        TSaltLinkPage r = new TSaltLinkPage();
        r.setSiteId(0);
        r.setNodeNo(node);
        r.setShouldCount(should);
        r.setDoneCount(done);
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
        p.setShouldCount(should);
        p.setDoneCount(done);
        p.setLackCount(lack);
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

    private TSaltEnt ent(Long id, String siteNo, int status) {
        TSaltEnt e = new TSaltEnt();
        e.setId(id);
        e.setSiteNo(siteNo);
        e.setSiteName("企业" + siteNo);
        e.setSiteType("制盐企业");
        e.setRoadName("盐岭省—盐泽市—临卤县");
        e.setStatus(status);
        e.setDelFlag(0);
        return e;
    }
}
