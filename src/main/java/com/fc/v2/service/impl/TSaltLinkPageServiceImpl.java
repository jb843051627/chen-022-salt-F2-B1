package com.fc.v2.service.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.TSaltEntMapper;
import com.fc.v2.mapper.auto.TSaltLinkPageMapper;
import com.fc.v2.model.auto.TSaltEnt;
import com.fc.v2.model.auto.TSaltLinkPage;
import com.fc.v2.model.custom.SaltBoardQuery;
import com.fc.v2.model.custom.SaltBoardResult;
import com.fc.v2.service.ITSaltLinkPageService;
import com.fc.v2.util.SnowflakeIdWorker;
import com.fc.v2.util.StringUtils;
import org.springframework.stereotype.Service;

/**
 * 品种挂接页Service业务层处理。
 *
 * 四件事只在这一层定夺，接口层与屏上不再另摆判路：
 * 1）一页合不合（应挂/已挂/挂靠层/所对企业的校验）；
 * 2）欠挂品种数折出几（应挂-已挂，起页、已挂数一变、换挂靠层三个场合重算后写回）；
 * 3）按属地那一路该露哪几页（压在那一层底下，县不见邻县、市不夹别市）；
 * 4）名录页三栏（在册/已挂讫/压页）与逐页行册同一次算。
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TSaltLinkPageServiceImpl extends ServiceImpl<TSaltLinkPageMapper, TSaltLinkPage> implements ITSaltLinkPageService {

    /** 属地分段分隔符：省—市—县 */
    private static final String ROAD_SEP = "—";

    /** 挂接进展 0待挂 1已挂讫 2压页 */
    private static final int STATUS_PENDING = 0;
    private static final int STATUS_DONE = 1;
    private static final int STATUS_HELD = 2;

    /** 名录情形 0在册 1已摘牌 */
    private static final int ENT_DELISTED = 1;

    /** 类别固定次序 */
    private static final List<String> SITE_TYPE_ORDER =
            Arrays.asList("制盐企业", "省内批发企业", "跨省批发企业");

    private final TSaltEntMapper saltEntMapper;

    public TSaltLinkPageServiceImpl(TSaltEntMapper saltEntMapper) {
        this.saltEntMapper = saltEntMapper;
    }

    @Override
    public TSaltLinkPage selectTSaltLinkPageById(Long id) {
        return this.baseMapper.selectOne(new QueryWrapper<TSaltLinkPage>()
                .eq("id", id)
                .eq("del_flag", 0));
    }

    @Override
    public List<TSaltLinkPage> selectTSaltLinkPageList(Wrapper<TSaltLinkPage> queryWrapper) {
        if (queryWrapper instanceof QueryWrapper) {
            ((QueryWrapper<TSaltLinkPage>) queryWrapper).eq("del_flag", 0);
            return this.baseMapper.selectList(queryWrapper);
        }
        return this.baseMapper.selectList(new QueryWrapper<TSaltLinkPage>().eq("del_flag", 0));
    }

    // ------------------------------------------------------------------
    // 起页（头一回）
    // ------------------------------------------------------------------

    @Override
    public int insertTSaltLinkPage(TSaltLinkPage record) {
        // 维持原签名：老来路拿不到回执细节，只知页起没起来；
        // 要说清毛病出在哪一栏，走同义方法 openLinkPage。
        try {
            openLinkPage(record);
            return 1;
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    @Override
    public TSaltLinkPage openLinkPage(TSaltLinkPage record) {
        if (record == null) {
            throw new IllegalArgumentException("递上来的是空页，起不来");
        }

        TSaltEnt ent = loadEnt(record.getSiteId());
        // 摘了牌的企业不再开新页
        if (ent.getStatus() != null && ent.getStatus() == ENT_DELISTED) {
            throw new IllegalArgumentException("所对定点企业已摘牌，不再开新页");
        }

        int nodeNo = normalizeNodeNo(record.getNodeNo());
        int should = requireShould(record.getShouldCount());
        int done = normalizeDone(record.getDoneCount());
        validateCounts(should, done);

        // 两处码子归系统发：人手写一个递上来不收，当场抹掉重发
        record.setBillNo(issueBillNo());
        record.setSignCode(issueSignCode());
        // 开页人归登录人：表单里即便塞了 createBy 也不认，抹掉交给填充器落登录名
        record.setCreateBy(null);
        record.setUpdateBy(null);
        record.setSiteNo(ent.getSiteNo());
        record.setScopeRoad(pressRoad(ent.getRoadName(), nodeNo));
        record.setNodeNo(nodeNo);
        record.setShouldCount(should);
        record.setDoneCount(done);
        // 欠挂不劳人报：交来的值不认，由服务层折出写回
        record.setLackCount(should - done);
        // 进展也归系统管：头一回起页一律待挂
        record.setStatus(STATUS_PENDING);
        record.setDelFlag(0);

        this.baseMapper.insert(record);
        return record;
    }

    // ------------------------------------------------------------------
    // 重挂保存（已挂数一变 / 换了挂靠那一层）
    // ------------------------------------------------------------------

    @Override
    public int updateTSaltLinkPage(TSaltLinkPage record) {
        try {
            resaveLinkPage(record);
            return 1;
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    @Override
    public TSaltLinkPage resaveLinkPage(TSaltLinkPage record) {
        if (record == null || record.getId() == null) {
            throw new IllegalArgumentException("没点名要重挂哪一页（id 空着）");
        }
        TSaltLinkPage old = this.baseMapper.selectById(record.getId());
        if (old == null || (old.getDelFlag() != null && old.getDelFlag() == 1)) {
            throw new IllegalArgumentException("这一页在库里找不着");
        }

        // 所对企业：交了新值按新值，没交沿用旧值。
        // 旧页所对的企业哪怕后来摘了牌，页照旧算在册、照旧能改——摘牌只拦新开页。
        Integer siteId = record.getSiteId() != null ? record.getSiteId() : old.getSiteId();
        TSaltEnt ent = loadEnt(siteId);

        int nodeNo = normalizeNodeNo(record.getNodeNo() != null ? record.getNodeNo() : old.getNodeNo());
        int should = requireShould(record.getShouldCount() != null
                ? record.getShouldCount() : old.getShouldCount());
        int done = normalizeDone(record.getDoneCount() != null
                ? record.getDoneCount() : old.getDoneCount());
        validateCounts(should, done);

        // 挂接代号不换手；签认码、欠挂都不认人手交来的值
        record.setBillNo(old.getBillNo());
        record.setSignCode(old.getSignCode());
        record.setSiteId(Integer.valueOf(ent.getId().intValue()));
        record.setSiteNo(ent.getSiteNo());
        record.setScopeRoad(pressRoad(ent.getRoadName(), nodeNo));
        record.setNodeNo(nodeNo);
        record.setShouldCount(should);
        record.setDoneCount(done);
        record.setLackCount(should - done);
        record.setStatus(old.getStatus() == null ? STATUS_PENDING : old.getStatus());
        record.setDelFlag(0);
        record.setUpdateTime(new Date());

        this.baseMapper.updateById(record);
        return this.baseMapper.selectById(record.getId());
    }

    // ------------------------------------------------------------------
    // 名录页一屏
    // ------------------------------------------------------------------

    @Override
    public SaltBoardResult openBoard(SaltBoardQuery query, TSysUserView operator) {
        SaltBoardResult result = new SaltBoardResult();
        if (operator == null || StringUtils.isEmpty(operator.getDeptName())) {
            throw new IllegalArgumentException("操作人没有属地，名录页开不了");
        }
        result.setScopeRoad(operator.getDeptName());

        // 行册与三栏同一勺：同一属地口径、同一套寻页条件一次捞回，
        // 三栏就这批行点数，行册再从这批行里切一屏。
        // 导出（listBoardRows）走同一个 scoopPages，不会长出第二种数。
        List<TSaltLinkPage> all = scoopPages(query, operator);

        int registered = 0;
        int done = 0;
        int held = 0;
        for (TSaltLinkPage p : all) {
            registered++;
            if (p.getStatus() != null && p.getStatus() == STATUS_DONE) {
                done++;
            } else if (p.getStatus() != null && p.getStatus() == STATUS_HELD) {
                held++;
            }
        }

        int pageNo = query == null || query.getPage() < 1 ? 1 : query.getPage();
        int limit = query == null || query.getLimit() < 1 ? 10 : query.getLimit();
        int from = Math.min((pageNo - 1) * limit, all.size());
        int to = Math.min(from + limit, all.size());

        List<TSaltLinkPage> pageRows = new ArrayList<TSaltLinkPage>(all.subList(from, to));
        fillEntInfo(pageRows);

        result.setRows(pageRows);
        result.setTotal(all.size());
        result.setRegisteredCount(registered);
        result.setDoneCount(done);
        result.setHeldCount(held);
        return result;
    }

    /**
     * 导出名册：与名录页列表同一个 scoopPages——列表筛出哪些页，
     * 导出就装哪些页（含企业全称、类别一并补上），只是不分屏。
     */
    @Override
    public List<TSaltLinkPage> listBoardRows(SaltBoardQuery query, TSysUserView operator) {
        List<TSaltLinkPage> rows = scoopPages(query, operator);
        fillEntInfo(rows);
        return rows;
    }

    /**
     * 名录页行册的唯一来路：属地（del_flag/node_no/scope_road）钉死本层本路，
     * 再叠寻页条件（挂接代号/企业代号/进展/企业类别）。列表与导出都从这里舀。
     */
    private List<TSaltLinkPage> scoopPages(SaltBoardQuery query, TSysUserView operator) {
        QueryWrapper<TSaltLinkPage> w = new QueryWrapper<TSaltLinkPage>()
                .eq("del_flag", 0)
                .eq("node_no", operator.getLevel())
                .eq("scope_road", operator.getDeptName());

        if (query != null) {
            if (StringUtils.isNotEmpty(query.getBillNo())) {
                w.like("bill_no", query.getBillNo().trim());
            }
            if (StringUtils.isNotEmpty(query.getSiteNo())) {
                w.like("site_no", query.getSiteNo().trim());
            }
            if (query.getStatus() != null
                    && query.getStatus() >= STATUS_PENDING && query.getStatus() <= STATUS_HELD) {
                w.eq("status", query.getStatus());
            }
            if (StringUtils.isNotEmpty(query.getSiteType())) {
                // 企业类别在企业总名录上：先按类别（咬中几个字也算）圈出企业代号
                List<String> siteNos = siteNosByTypeLike(query.getSiteType().trim());
                if (siteNos.isEmpty()) {
                    return new ArrayList<TSaltLinkPage>();
                }
                w.in("site_no", siteNos);
            }
        }
        w.orderByDesc("create_time").orderByDesc("id");
        return this.baseMapper.selectList(w);
    }

    /** 给本屏逐页补上企业全称、类别（批量查一次总名录；摘牌企业也照补，旧页照旧在册） */
    private void fillEntInfo(List<TSaltLinkPage> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<String> nos = new ArrayList<String>();
        for (TSaltLinkPage p : rows) {
            if (StringUtils.isNotEmpty(p.getSiteNo()) && !nos.contains(p.getSiteNo())) {
                nos.add(p.getSiteNo());
            }
        }
        if (nos.isEmpty()) {
            return;
        }
        List<TSaltEnt> ents = saltEntMapper.selectList(new QueryWrapper<TSaltEnt>()
                .in("site_no", nos));
        java.util.Map<String, TSaltEnt> byNo = new java.util.HashMap<String, TSaltEnt>();
        for (TSaltEnt e : ents) {
            byNo.put(e.getSiteNo(), e);
        }
        for (TSaltLinkPage p : rows) {
            TSaltEnt e = byNo.get(p.getSiteNo());
            if (e != null) {
                p.setSiteName(e.getSiteName());
                p.setSiteType(e.getSiteType());
            }
        }
    }

    @Override
    public List<TSaltEnt> listEntRegister(String siteType) {
        QueryWrapper<TSaltEnt> w = new QueryWrapper<TSaltEnt>().eq("del_flag", 0);
        if (StringUtils.isNotEmpty(siteType)) {
            w.like("site_type", siteType.trim());
        }
        // 制盐企业、省内批发企业、跨省批发企业各按各的次序排
        w.last("order by field(site_type,"
                + "'" + SITE_TYPE_ORDER.get(0) + "',"
                + "'" + SITE_TYPE_ORDER.get(1) + "',"
                + "'" + SITE_TYPE_ORDER.get(2) + "'), road_name, site_no");
        return saltEntMapper.selectList(w);
    }

    @Override
    public TSaltEnt changeEntStatus(Long id, Integer status) {
        if (id == null) {
            throw new IllegalArgumentException("没点名是名录上哪一家");
        }
        if (status == null || (status != 0 && status != 1)) {
            throw new IllegalArgumentException("名录情形只许 0在册 / 1已摘牌");
        }
        TSaltEnt ent = saltEntMapper.selectById(id);
        if (ent == null || (ent.getDelFlag() != null && ent.getDelFlag() == 1)) {
            throw new IllegalArgumentException("这一家在总名录上找不着");
        }
        TSaltEnt update = new TSaltEnt();
        update.setId(id);
        update.setStatus(status);
        update.setUpdateTime(new Date());
        saltEntMapper.updateById(update);
        ent.setStatus(status);
        return ent;
    }

    @Override
    public boolean billNoInRegister(String billNo) {
        if (StringUtils.isEmpty(billNo)) {
            return false;
        }
        Integer cnt = this.baseMapper.selectCount(new QueryWrapper<TSaltLinkPage>()
                .eq("bill_no", billNo.trim()).eq("del_flag", 0));
        return cnt != null && cnt > 0;
    }

    @Override
    public TSaltLinkPage applyLinkDone(String billNo, int delta) {
        if (StringUtils.isEmpty(billNo) || (delta != 1 && delta != -1)) {
            throw new IllegalArgumentException("联动只认封卷(+1)或挪回(-1)");
        }
        TSaltLinkPage page = this.baseMapper.selectOne(new QueryWrapper<TSaltLinkPage>()
                .eq("bill_no", billNo).eq("del_flag", 0));
        if (page == null) {
            throw new IllegalArgumentException("挂接代号 " + billNo + " 在挂接簿上找不着");
        }
        int should = requireShould(page.getShouldCount());
        int done = normalizeDone(page.getDoneCount());
        int next = done + delta;
        if (next < 0 || next > should) {
            throw new IllegalArgumentException(
                    "已挂品种数(" + next + ")越出应挂(" + should + ")的界，这一笔联动落不下");
        }
        // 只动已挂与欠挂、随齐没齐翻进展；应挂品种数不碰——领单子不抬应挂。
        Integer nextStatus;
        if (next >= should) {
            nextStatus = STATUS_DONE;                 // 封卷把最后一格补齐 → 已挂讫
        } else if (delta == -1 && done >= should) {
            nextStatus = STATUS_PENDING;             // 挪回把已挂讫拆回 → 待挂，可逆
        } else {
            nextStatus = page.getStatus() == null ? STATUS_PENDING : page.getStatus();
        }
        TSaltLinkPage update = new TSaltLinkPage();
        update.setId(page.getId());
        update.setDoneCount(next);
        update.setLackCount(should - next);
        update.setStatus(nextStatus);
        update.setUpdateTime(new Date());
        this.baseMapper.updateById(update);
        return this.baseMapper.selectById(page.getId());
    }

    // ------------------------------------------------------------------
    // 进展逐态推进 / 退回
    // ------------------------------------------------------------------

    @Override
    public int advanceTSaltLinkPage(Long id) {
        if (id == null) {
            return 0;
        }
        TSaltLinkPage cur = this.baseMapper.selectById(id);
        if (cur == null || cur.getDelFlag() == null || cur.getDelFlag() == 1) {
            return 0;
        }
        int st = cur.getStatus() == null ? STATUS_PENDING : cur.getStatus();
        // 守边界：已挂讫不能再推，不允许跳态
        if (st >= STATUS_HELD) {
            return 0;
        }
        int next = st + 1;
        // 推进时若签认码缺位则补发，不由人手填
        String signCode = StringUtils.isNotEmpty(cur.getSignCode()) ? cur.getSignCode() : issueSignCode();
        TSaltLinkPage update = new TSaltLinkPage();
        update.setId(id);
        update.setStatus(next);
        update.setSignCode(signCode);
        update.setUpdateTime(new Date());
        return this.baseMapper.updateById(update);
    }

    @Override
    public int revertTSaltLinkPage(Long id) {
        if (id == null) {
            return 0;
        }
        TSaltLinkPage cur = this.baseMapper.selectById(id);
        if (cur == null || cur.getDelFlag() == null || cur.getDelFlag() == 1) {
            return 0;
        }
        if (cur.getStatus() == null) {
            return 0;
        }
        int st = cur.getStatus();
        // 守退回范围：待挂无处可退
        if (st <= STATUS_PENDING) {
            return 0;
        }
        TSaltLinkPage update = new TSaltLinkPage();
        update.setId(id);
        update.setStatus(st - 1);
        // 签认码保留，不清空；旧记录保留在册
        update.setUpdateTime(new Date());
        return this.baseMapper.updateById(update);
    }

    @Override
    public int deleteTSaltLinkPageByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        if (idArr == null || idArr.length == 0) {
            return 0;
        }
        int rows = 0;
        for (Long id : idArr) {
            TSaltLinkPage update = new TSaltLinkPage();
            update.setId(id);
            update.setDelFlag(1);
            update.setUpdateTime(new Date());
            rows += this.baseMapper.updateById(update);
        }
        return rows;
    }

    @Override
    public int deleteTSaltLinkPageById(Long id) {
        if (id == null) {
            return 0;
        }
        TSaltLinkPage update = new TSaltLinkPage();
        update.setId(id);
        update.setDelFlag(1);
        update.setUpdateTime(new Date());
        return this.baseMapper.updateById(update);
    }

    // ------------------------------------------------------------------
    // 校验与折数——两条起页来路、重挂保存都走这一套说法
    // ------------------------------------------------------------------

    private TSaltEnt loadEnt(Integer siteId) {
        if (siteId == null) {
            throw new IllegalArgumentException("所对定点企业那一栏空着，页起不来");
        }
        TSaltEnt ent = saltEntMapper.selectOne(new QueryWrapper<TSaltEnt>()
                .eq("id", siteId).eq("del_flag", 0));
        if (ent == null) {
            throw new IllegalArgumentException("所对定点企业不在总名录上，页起不来");
        }
        return ent;
    }

    private int requireShould(Integer should) {
        if (should == null) {
            throw new IllegalArgumentException("应挂品种数那一栏空着递，这一页起不来");
        }
        if (should < 0) {
            throw new IllegalArgumentException("应挂品种数那一栏递进来是负数，这一页起不来");
        }
        return should;
    }

    private int normalizeDone(Integer done) {
        if (done == null) {
            return 0;
        }
        if (done < 0) {
            throw new IllegalArgumentException("已挂品种数那一栏递进来是负数，进不了库");
        }
        return done;
    }

    private void validateCounts(int should, int done) {
        if (done > should) {
            // 同一毛病从两面都说得通：应挂低过已挂 / 已挂高过应挂，回执把两栏都点出来
            throw new IllegalArgumentException(
                    "已挂品种数(" + done + ")高过应挂品种数(" + should + ")，进不了库");
        }
    }

    private int normalizeNodeNo(Integer nodeNo) {
        // 属地写到县为止：不点明压在哪一层，就默认压在县
        int n = nodeNo == null ? 2 : nodeNo;
        if (n < 0 || n > 2) {
            throw new IllegalArgumentException("适用区域层级序那一栏只许 0省/1市/2县");
        }
        return n;
    }

    /** 页压在企业属地那一层：截到第 nodeNo+1 段（0省 1市 2县） */
    private String pressRoad(String roadName, int nodeNo) {
        if (StringUtils.isEmpty(roadName)) {
            throw new IllegalArgumentException("所对企业的属地那一路空着，页压不下去");
        }
        String[] parts = roadName.split(ROAD_SEP);
        if (parts.length < nodeNo + 1) {
            throw new IllegalArgumentException("所对企业的属地没写到县，页压不到第 " + nodeNo + " 层");
        }
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i <= nodeNo; i++) {
            sb.append(ROAD_SEP).append(parts[i]);
        }
        return sb.toString();
    }

    private List<String> siteNosByTypeLike(String typeKeyword) {
        List<TSaltEnt> ents = saltEntMapper.selectList(new QueryWrapper<TSaltEnt>()
                .select("site_no")
                .eq("del_flag", 0)
                .like("site_type", typeKeyword));
        List<String> nos = new ArrayList<String>();
        for (TSaltEnt e : ents) {
            if (StringUtils.isNotEmpty(e.getSiteNo())) {
                nos.add(e.getSiteNo());
            }
        }
        return nos;
    }

    /** 挂接代号：系统发，人手不收 */
    private String issueBillNo() {
        String no;
        do {
            no = "GJ" + SnowflakeIdWorker.getUUID();
        } while (billNoExists(no));
        return no;
    }

    /** 随行签认码：系统发，人手不收 */
    private String issueSignCode() {
        return "QR" + SnowflakeIdWorker.getUUID();
    }

    private boolean billNoExists(String billNo) {
        Integer cnt = this.baseMapper.selectCount(new QueryWrapper<TSaltLinkPage>()
                .eq("bill_no", billNo));
        return cnt != null && cnt > 0;
    }
}
