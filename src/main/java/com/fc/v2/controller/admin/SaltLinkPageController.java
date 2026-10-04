package com.fc.v2.controller.admin;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.model.auto.TSysDepartment;
import com.fc.v2.model.auto.TSysUser;
import com.fc.v2.model.custom.SaltBoardQuery;
import com.fc.v2.model.custom.SaltBoardResult;
import com.fc.v2.model.custom.SaltLinkPageForm;
import com.fc.v2.model.auto.TSaltLinkPage;
import com.fc.v2.service.ITSysDepartmentService;
import com.fc.v2.service.ITSaltLinkPageService;
import com.fc.v2.shiro.util.ShiroUtils;
import com.fc.v2.util.StringUtils;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 企业品种挂接簿页 Controller。
 * 页合不合、欠挂折几、该露哪几页、名录页三栏几个数，全在服务层那条保存/开屏方法里定；
 * 本层只收表单、点名叫出毛病，不另摆一处能躲开它的判路。
 *
 * @author fuce
 * @date 2026-09-12
 */
@Api(value = "企业品种挂接簿页")
@Controller
@RequestMapping("/SaltLinkPageController")
public class SaltLinkPageController extends BaseController {

    private final String prefix = "admin/saltLinkPage";

    @Autowired
    private ITSaltLinkPageService saltLinkPageService;

    @Autowired
    private ITSysDepartmentService sysDepartmentService;

    @ApiOperation(value = "分页跳转", notes = "分页跳转")
    @GetMapping("/view")
    @RequiresPermissions("salt:saltLinkPage:view")
    public String view(ModelMap model) {
        return prefix + "/list";
    }

    /**
     * 名录页一屏：谁家的屏露谁家的页。
     * 三栏（在册/已挂讫/压页）随本屏行册一起回来，出自服务层同一次算。
     */
    @ApiOperation(value = "名录页分页查询", notes = "按属地那一路圈定，三栏同算")
    @GetMapping("/list")
    @RequiresPermissions("salt:saltLinkPage:list")
    @ResponseBody
    public AjaxResult list(@RequestParam(value = "page", defaultValue = "1") int page,
                           @RequestParam(value = "limit", defaultValue = "10") int limit,
                           @RequestParam(value = "billNo", required = false) String billNo,
                           @RequestParam(value = "siteNo", required = false) String siteNo,
                           @RequestParam(value = "siteType", required = false) String siteType,
                           @RequestParam(value = "status", required = false) Integer status) {
        SaltBoardQuery query = buildQuery(page, limit, billNo, siteNo, siteType, status);

        SaltBoardResult board;
        try {
            board = saltLinkPageService.openBoard(query, currentOperator());
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(e.getMessage());
        }

        // 行册与三栏同一次算，原样摊在同一回执上
        AjaxResult json = AjaxResult.successData(0, board.getRows());
        json.put("msg", "");
        json.put("count", board.getTotal());
        json.put("registeredCount", board.getRegisteredCount());
        json.put("doneCount", board.getDoneCount());
        json.put("heldCount", board.getHeldCount());
        json.put("scopeRoad", board.getScopeRoad());
        return json;
    }

    /**
     * 导出：与列表同一把勺子（scoopBoard），同样的属地、筛选、两位小数口径，
     * 不分页整勺端走。屏上几栏、什么次序，导出就是几栏、什么次序，数字不会对不上。
     */
    @ApiOperation(value = "名录页导出", notes = "与列表同一次取数口径，CSV 整勺导出")
    @GetMapping("/export")
    @RequiresPermissions("salt:saltLinkPage:list")
    public void export(@RequestParam(value = "billNo", required = false) String billNo,
                       @RequestParam(value = "siteNo", required = false) String siteNo,
                       @RequestParam(value = "siteType", required = false) String siteType,
                       @RequestParam(value = "status", required = false) Integer status,
                       HttpServletResponse response) throws IOException {
        SaltBoardQuery query = buildQuery(1, Integer.MAX_VALUE, billNo, siteNo, siteType, status);
        List<TSaltLinkPage> rows;
        try {
            rows = saltLinkPageService.scoopBoard(query, currentOperator());
        } catch (IllegalArgumentException e) {
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write(e.getMessage());
            return;
        }

        response.setContentType("text/csv;charset=UTF-8");
        String fileName = URLEncoder.encode("企业品种挂接簿.csv", StandardCharsets.UTF_8.name()).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment;filename*=UTF-8''" + fileName);
        // UTF-8 BOM，Excel 打开不乱码
        response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});

        StringBuilder sb = new StringBuilder();
        sb.append("挂接代号,企业代号,企业全称,企业类别,挂靠层,归属地那一路,应挂,已挂,欠挂,进展,开单人,开单时间\r\n");
        for (TSaltLinkPage p : rows) {
            sb.append(csv(p.getBillNo())).append(',')
                    .append(csv(p.getSiteNo())).append(',')
                    .append(csv(p.getSiteName())).append(',')
                    .append(csv(p.getSiteType())).append(',')
                    .append(csv(nodeName(p.getNodeNo()))).append(',')
                    .append(csv(p.getScopeRoad())).append(',')
                    .append(num(p.getShouldCount())).append(',')
                    .append(num(p.getDoneCount())).append(',')
                    .append(num(p.getLackCount())).append(',')
                    .append(csv(statusName(p.getStatus()))).append(',')
                    .append(csv(p.getCreateBy())).append(',')
                    .append(p.getCreateTime() == null ? "" : String.format("%tF %<tT", p.getCreateTime()))
                    .append("\r\n");
        }
        response.getOutputStream().write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    @ApiOperation(value = "企业总名录跳转", notes = "企业总名录跳转")
    @GetMapping("/entView")
    @RequiresPermissions("salt:saltLinkPage:view")
    public String entView(ModelMap model) {
        return prefix + "/entList";
    }

    /** 企业总名录：一家一行，类别按制盐/省内批发/跨省批发固定次序 */
    @ApiOperation(value = "企业总名录查询", notes = "一家一行，类别固定次序")
    @GetMapping("/entList")
    @RequiresPermissions("salt:saltLinkPage:list")
    @ResponseBody
    public AjaxResult entList(@RequestParam(value = "siteType", required = false) String siteType) {
        return AjaxResult.successData(0, saltLinkPageService.listEntRegister(siteType));
    }

    /**
     * 企业总名录状态变更（停用=摘牌 / 恢复=在册）。挂接页开页时认的就是这一个状态，
     * 名录这边改了，起页口子立刻拦住——两个模块不各存一套口径。
     */
    @ApiOperation(value = "企业总名录状态变更", notes = "停用(摘牌)/恢复(在册)")
    @PostMapping("/entStatus")
    @RequiresPermissions("salt:saltLinkPage:edit")
    @ResponseBody
    public AjaxResult entStatus(@RequestParam("id") Long id, @RequestParam("status") Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            return AjaxResult.error("名录情形只许 0在册 / 1已摘牌");
        }
        try {
            saltLinkPageService.changeEntRegisterStatus(id, status, ShiroUtils.getLoginName());
            return AjaxResult.success(status == 1 ? "已停用（摘牌），该企业不能再起新页" : "已恢复在册");
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @ApiOperation(value = "新增起页跳转", notes = "新增起页跳转")
    @GetMapping("/add")
    @RequiresPermissions("salt:saltLinkPage:add")
    public String add(ModelMap model) {
        return prefix + "/add";
    }

    @ApiOperation(value = "修改重挂跳转", notes = "修改重挂跳转")
    @GetMapping("/edit/{id}")
    @RequiresPermissions("salt:saltLinkPage:edit")
    public String edit(@PathVariable("id") Long id, ModelMap model) {
        model.put("page", saltLinkPageService.selectTSaltLinkPageById(id));
        return prefix + "/edit";
    }

    /**
     * 头一回起页。数目按原文收，空着/负数在这一层点名叫出，
     * 小数折成档案口径的两位（四舍五入）后交服务层同一套校验——与 /edit 是同一份折法、同一种回执。
     */
    @ApiOperation(value = "起页", notes = "头一回起页，系统发挂接代号与签认码")
    @PostMapping("/add")
    @RequiresPermissions("salt:saltLinkPage:add")
    @ResponseBody
    public AjaxResult addSave(SaltLinkPageForm form) {
        TSaltLinkPage record;
        try {
            record = bindPage(form, null);
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(e.getMessage());
        }
        try {
            TSaltLinkPage saved = saltLinkPageService.openLinkPage(record, ShiroUtils.getLoginName());
            return AjaxResult.success("起页成功，挂接代号：" + saved.getBillNo());
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    /**
     * 重挂保存（已挂数一变 / 换了挂靠那一层）。与起页同一个 bindPage、同一套服务校验。
     */
    @ApiOperation(value = "重挂保存", notes = "已挂数变动或换挂靠层后重算欠挂")
    @PostMapping("/edit")
    @RequiresPermissions("salt:saltLinkPage:edit")
    @ResponseBody
    public AjaxResult editSave(SaltLinkPageForm form) {
        if (form == null || StringUtils.isEmpty(form.getId())) {
            return AjaxResult.error("没点名要重挂哪一页（id 空着）");
        }
        Long id;
        try {
            id = Long.valueOf(form.getId().trim());
        } catch (NumberFormatException e) {
            return AjaxResult.error("页号那一栏不是个整数");
        }
        TSaltLinkPage record;
        try {
            record = bindPage(form, id);
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(e.getMessage());
        }
        try {
            saltLinkPageService.resaveLinkPage(record, ShiroUtils.getLoginName());
            return AjaxResult.success("重挂保存成功");
        } catch (IllegalArgumentException e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @ApiOperation(value = "推进一态", notes = "待挂→已挂讫→压页")
    @PostMapping("/advance")
    @RequiresPermissions("salt:saltLinkPage:edit")
    @ResponseBody
    public AjaxResult advance(@RequestParam("id") Long id) {
        return toAjax(saltLinkPageService.advanceTSaltLinkPage(id));
    }

    @ApiOperation(value = "退回一态", notes = "退回上一态，旧页仍在册")
    @PostMapping("/revert")
    @RequiresPermissions("salt:saltLinkPage:edit")
    @ResponseBody
    public AjaxResult revert(@RequestParam("id") Long id) {
        return toAjax(saltLinkPageService.revertTSaltLinkPage(id));
    }

    @ApiOperation(value = "删除", notes = "删页（沉底，不真抹）")
    @DeleteMapping("/remove")
    @RequiresPermissions("salt:saltLinkPage:remove")
    @ResponseBody
    public AjaxResult remove(String ids) {
        return toAjax(saltLinkPageService.deleteTSaltLinkPageByIds(ids));
    }

    // ------------------------------------------------------------------
    // 两条起页来路共用的同一份折法——屏上长不出第二种结果
    // ------------------------------------------------------------------

    private SaltBoardQuery buildQuery(int page, int limit, String billNo, String siteNo,
                                      String siteType, Integer status) {
        SaltBoardQuery query = new SaltBoardQuery();
        query.setPage(page);
        query.setLimit(limit);
        query.setBillNo(billNo);
        query.setSiteNo(siteNo);
        query.setSiteType(siteType);
        query.setStatus(status);
        return query;
    }

    private TSaltLinkPage bindPage(SaltLinkPageForm form, Long id) {
        if (form == null) {
            throw new IllegalArgumentException("递上来的是空页，起不来");
        }
        TSaltLinkPage record = new TSaltLinkPage();
        record.setId(id);
        record.setRemark(form.getRemark());

        String siteIdRaw = form.getSiteId();
        if (StringUtils.isEmpty(siteIdRaw)) {
            throw new IllegalArgumentException("所对定点企业那一栏空着，页起不来");
        }
        try {
            record.setSiteId(Integer.valueOf(siteIdRaw.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("所对定点企业那一栏没点中总名录上的企业");
        }

        String nodeRaw = form.getNodeNo();
        if (StringUtils.isNotEmpty(nodeRaw)) {
            try {
                int node = Integer.valueOf(nodeRaw.trim());
                if (node < 0 || node > 2) {
                    throw new IllegalArgumentException("适用区域层级序那一栏只许 0省/1市/2县");
                }
                record.setNodeNo(node);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("适用区域层级序那一栏只许填 0、1、2");
            }
        }

        record.setShouldCount(parseCount(form.getShouldCount(), "应挂品种数"));
        record.setDoneCount(parseCount(form.getDoneCount(), "已挂品种数"));
        return record;
    }

    /**
     * 数目原文折数：空着、不是数、负数，一律点名叫出是哪一栏。
     * 小数照收，但只按档案口径留两位（四舍五入）；多于两位的在接口层先折，不许原样灌进库。
     */
    private BigDecimal parseCount(String raw, String column) {
        if (StringUtils.isEmpty(raw)) {
            // 已挂空着按0起算；应挂空着不许起（服务层再把这道关）
            if ("应挂品种数".equals(column)) {
                return null;
            }
            return BigDecimal.ZERO.setScale(2);
        }
        String s = raw.trim();
        BigDecimal v;
        try {
            v = new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(column + "那一栏不是个数（" + s + "），这一页起不来");
        }
        if (v.signum() < 0) {
            throw new IllegalArgumentException(column + "那一栏递进来是负数（" + s + "），这一页起不来");
        }
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private String csv(String v) {
        if (v == null) {
            return "";
        }
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    private String num(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    private String nodeName(Integer node) {
        if (node == null) {
            return "";
        }
        return node == 0 ? "省" : node == 1 ? "市" : "县";
    }

    private String statusName(Integer status) {
        if (status == null) {
            return "待挂";
        }
        return status == 1 ? "已挂讫" : status == 2 ? "压页" : "待挂";
    }

    /**
     * 取登录人压在哪一层：沿部门父链走到顶，根为省(0)、往下市(1)、县(2)；
     * 县以下的账号按县一层算。页压在哪一层、露哪一路，由此定。
     */
    private ITSaltLinkPageService.TSysUserView currentOperator() {
        TSysUser user = ShiroUtils.getUser();
        if (user == null || user.getDepId() == null) {
            throw new IllegalArgumentException("当前登录人没有属地部门，开不了名录页");
        }
        List<String> names = new ArrayList<String>();
        Long cur = user.getDepId();
        int guard = 0;
        while (cur != null && cur != 0L && guard++ < 20) {
            TSysDepartment dept = sysDepartmentService.selectTSysDepartmentById(cur);
            if (dept == null) {
                break;
            }
            names.add(0, dept.getDeptName());
            cur = dept.getParentId();
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException("当前登录人没有属地部门，开不了名录页");
        }
        // 根为省(0)、往下市(1)、县(2)；县以下的账号按县一层算。
        // 交给服务层的是该层及以上接成的那一条属地路，与企业 road_name 前缀同一写法。
        int depth = Math.min(names.size() - 1, 2);
        StringBuilder road = new StringBuilder(names.get(0));
        for (int i = 1; i <= depth; i++) {
            road.append("—").append(names.get(i));
        }
        return new ITSaltLinkPageService.TSysUserView(road.toString(), depth);
    }
}
