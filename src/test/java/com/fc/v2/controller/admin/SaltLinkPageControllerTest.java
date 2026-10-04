package com.fc.v2.controller.admin;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fc.v2.model.auto.TSaltLinkPage;
import com.fc.v2.service.ITSaltLinkPageService;
import com.fc.v2.service.ITSysDepartmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 两条起页来路（/add 头一回起页、/edit 重挂保存）按同一套说法办：
 * 空着、带小数、负数的回执措辞一致；服务层判下的毛病原样回，接口层不另立判路。
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
    public void add_decimalShould_sameRejectAsEdit() throws Exception {
        String addMsg = postAdd("10.5", "3").andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String editMsg = postEdit("10.5", "3").andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(addMsg.contains("应挂品种数"), addMsg);
        assertTrue(addMsg.contains("小数"), addMsg);
        // 两条来路长的是同一种结果
        assertTrue(addMsg.equals(editMsg), "add=" + addMsg + " edit=" + editMsg);
        verify(service, never()).openLinkPage(any());
        verify(service, never()).resaveLinkPage(any());
    }

    @Test
    public void add_blankShould_blockedNamesColumn() throws Exception {
        // 应挂空着由服务层那一条保存方法判下，接口层原样回执
        when(service.openLinkPage(any()))
                .thenThrow(new IllegalArgumentException("应挂品种数那一栏空着递，这一页起不来"));
        postAdd("", "3")
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("应挂品种数")));
    }

    @Test
    public void add_negativeDone_blockedNamesColumn() throws Exception {
        postAdd("10", "-2")
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("已挂品种数")));
        verify(service, never()).openLinkPage(any());
    }

    @Test
    public void add_valid_callsServiceAndSystemCodeComesBack() throws Exception {
        TSaltLinkPage saved = new TSaltLinkPage();
        saved.setBillNo("GJ123");
        when(service.openLinkPage(any())).thenReturn(saved);

        postAdd("64", "41")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("GJ123")));
    }

    @Test
    public void bothRoutes_relayServiceRejectionDoneOverShould() throws Exception {
        String svcMsg = "已挂品种数(10)高过应挂品种数(9)，进不了库";
        when(service.openLinkPage(any())).thenThrow(new IllegalArgumentException(svcMsg));
        when(service.resaveLinkPage(any())).thenThrow(new IllegalArgumentException(svcMsg));

        String addMsg = postAdd("9", "10").andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String editMsg = postEdit("9", "10").andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(addMsg.contains("已挂品种数"), addMsg);
        assertTrue(addMsg.contains("应挂品种数"), addMsg);
        assertTrue(addMsg.equals(editMsg), "两条来路回执应一致");
    }

    private org.springframework.test.web.servlet.ResultActions postAdd(String should, String done) throws Exception {
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
