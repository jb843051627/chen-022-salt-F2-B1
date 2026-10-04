package com.fc.v2.controller.admin;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import com.fc.v2.model.auto.TSaltLinkPage;
import com.fc.v2.model.auto.TSysUser;
import com.fc.v2.service.ITSaltLinkPageService;
import com.fc.v2.service.ITSysDepartmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 两条起页来路（/add 头一回起页、/edit 重挂保存）按同一套说法办：
 * 小数照收但统一折成档案口径两位（四舍五入）；空着、负数由接口层/服务层点名叫出，
 * 措辞两条来路一致，接口层不另立判路。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class SaltLinkPageControllerTest {

    private MockMvc mvc;

    @Mock
    private ITSaltLinkPageService service;
    @Mock
    private ITSysDepartmentService departmentService;

    @BeforeEach
    public void setUp() {
        SaltLinkPageController c = new SaltLinkPageController();
        ReflectionTestUtils.setField(c, "saltLinkPageService", service);
        ReflectionTestUtils.setField(c, "sysDepartmentService", departmentService);
        mvc = MockMvcBuilders.standaloneSetup(c).build();
    }

    @Test
    public void add_fiveDecimals_roundToTwo_sameAsEdit() throws Exception {
        TSaltLinkPage saved = new TSaltLinkPage();
        saved.setBillNo("GJ1");
        when(service.openLinkPage(any(), any())).thenReturn(saved);
        when(service.resaveLinkPage(any(), any())).thenReturn(saved);

        // 五位小数不再被拒：两条来路都折成 10.24，库存/列表/导出同一口径
        postAdd("10.235", "3.004").andExpect(status().isOk());
        postEdit("10.235", "3.004").andExpect(status().isOk());

        ArgumentCaptor<TSaltLinkPage> addCap = ArgumentCaptor.forClass(TSaltLinkPage.class);
        verify(service).openLinkPage(addCap.capture(), any());
        assertEquals2("10.24", addCap.getValue().getShouldCount());
        assertEquals2("3.00", addCap.getValue().getDoneCount());

        ArgumentCaptor<TSaltLinkPage> editCap = ArgumentCaptor.forClass(TSaltLinkPage.class);
        verify(service).resaveLinkPage(editCap.capture(), any());
        // 两条来路长的是同一种折法
        assertEquals2("10.24", editCap.getValue().getShouldCount());
        assertEquals2("3.00", editCap.getValue().getDoneCount());
    }

    private static void assertEquals2(String expect, BigDecimal actual) {
        org.junit.jupiter.api.Assertions.assertEquals(0,
                new BigDecimal(expect).compareTo(actual),
                "期望折成" + expect + "，实际" + actual);
        org.junit.jupiter.api.Assertions.assertEquals(2, actual.scale());
    }

    @Test
    public void add_blankShould_blockedNamesColumn() throws Exception {
        // 应挂空着由服务层那一条保存方法判下，接口层原样回执
        when(service.openLinkPage(any(), any()))
                .thenThrow(new IllegalArgumentException("应挂品种数那一栏空着递，这一页起不来"));
        postAdd("", "3")
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("应挂品种数")));
    }

    @Test
    public void add_negativeDone_blockedNamesColumn() throws Exception {
        postAdd("10", "-2")
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("已挂品种数")));
        verify(service, never()).openLinkPage(any(), any());
    }

    @Test
    public void add_nonNumeric_blockedNamesColumn() throws Exception {
        postAdd("十", "2")
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("应挂品种数")));
        verify(service, never()).openLinkPage(any(), any());
    }

    @Test
    public void add_valid_callsServiceAndSystemCodeComesBack() throws Exception {
        TSaltLinkPage saved = new TSaltLinkPage();
        saved.setBillNo("GJ123");
        when(service.openLinkPage(any(), any())).thenReturn(saved);

        postAdd("64", "41")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("GJ123")));
    }

    @Test
    public void add_passesActualLoginOperator() throws Exception {
        TSaltLinkPage saved = new TSaltLinkPage();
        saved.setBillNo("GJ1");
        when(service.openLinkPage(any(), eq("开单人"))).thenReturn(saved);

        // 在 Shiro 线程上下文里放一个登录主体，接口层就该取它的登录名、不认表单
        TSysUser user = new TSysUser();
        user.setUsername("开单人");
        org.apache.shiro.mgt.DefaultSecurityManager sm = new org.apache.shiro.mgt.DefaultSecurityManager();
        org.apache.shiro.SecurityUtils.setSecurityManager(sm);
        try {
            org.apache.shiro.subject.Subject subject = new org.apache.shiro.subject.Subject.Builder(sm)
                    .principals(new org.apache.shiro.subject.SimplePrincipalCollection(user, "test"))
                    .authenticated(true)
                    .buildSubject();
            subject.execute(() -> {
                postAdd("64", "41").andExpect(status().isOk());
                verify(service).openLinkPage(any(), eq("开单人"));
                return null;
            });
        } finally {
            // 不还原会让同 JVM 后续用例拿到"空主体"而抛"用户不存在"
            org.apache.shiro.SecurityUtils.setSecurityManager(null);
            org.apache.shiro.util.ThreadContext.unbindSubject();
        }
    }

    @Test
    public void bothRoutes_relayServiceRejectionDoneOverShould() throws Exception {
        String svcMsg = "已挂品种数(10.00)高过应挂品种数(9.00)，进不了库";
        when(service.openLinkPage(any(), any())).thenThrow(new IllegalArgumentException(svcMsg));
        when(service.resaveLinkPage(any(), any())).thenThrow(new IllegalArgumentException(svcMsg));

        String addMsg = postAdd("9", "10").andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String editMsg = postEdit("9", "10").andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(addMsg.contains("已挂品种数"), addMsg);
        assertTrue(addMsg.contains("应挂品种数"), addMsg);
        assertTrue(addMsg.equals(editMsg), "两条来路回执应一致");
    }

    private org.springframework.test.web.servlet.ResultActions postAdd(String should, String done) throws Exception {
        // 无 Shiro 上下文时 ShiroUtils.getLoginName() 按定时任务兜底返回 "Task"
        return mvc.perform(post("/SaltLinkPageController/add")
                        .param("siteId", "0")
                        .param("nodeNo", "2")
                        .param("shouldCount", should)
                        .param("doneCount", done))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions postEdit(String should, String done) throws Exception {
        return mvc.perform(post("/SaltLinkPageController/edit")
                        .param("id", "7")
                        .param("siteId", "0")
                        .param("nodeNo", "2")
                        .param("shouldCount", should)
                        .param("doneCount", done))
                .andExpect(status().isOk());
    }
}
