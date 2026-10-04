package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * 口径：应挂/已挂/欠挂一律按档案的两位小数收，多于两位四舍五入折成两位，
 * 屏上列表与导出取的是同一次 scoop，行数、数目不会长出两张脸。
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
            openLinkPage(record, null);
            return 1;
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    @Override
    public TSaltLinkPage openLinkPage(TSaltLinkPage record) {
        return openLinkPage(record, null);
    }

    @Override
    public TSaltLinkPage openLinkPage(TSaltLinkPage record, String operator) {
        if (record == null) {
            throw new IllegalArgumentException("递上来的是空页，起不来");
        }

        TSaltEnt ent = loadEnt(record.getSiteId());
        // 摘了牌的企业不再开新页
        if (ent.getStatus() != null && ent.getStatus() == ENT_DELISTED) {
            throw new IllegalArgumentException("所对定点企业已摘牌，不再开新页");
        }

        // 两处码子归系统发：人手写一个递上来不收——不静默读没，当场点名叫出
        rejectHandedCode(record.getBillNo(), "挂接代号");
        rejectHandedCode(record.getSignCode(), "随行签认码");

        int nodeNo = normalizeNodeNo(record.getNodeNo());
        BigDecimal should = requireShould(record.getShouldCount());
        BigDecimal done = normalizeDone(record.getDoneCount());
        validateCounts(should, done);

        record.setBillNo(issueBillNo());
        record.setSignCode(issueSignCode());
        record.setSiteNo(ent.getSiteNo());
        record.setScopeRoad(pressRoad(ent.getRoadName(), nodeNo));
        record.setNodeNo(nodeNo);
        record.setShouldCount(should);
        record.setDoneCount(done);
        // 欠挂不劳人报：交来的值不认，由服务层折出写回
        record.setLackCount(should.subtract(done));
        // 进展也归系统管：头一回起页一律待挂
        record.setStatus(STATUS_PENDING);
        record.setDelFlag(0);
        // 起页署名钉死成实际开单人，不认表单上谁填了什么
        if (StringUtils.isNotEmpty(operator)) {
            record.setCreateBy(operator);
        }

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
        return resaveLinkPage(record, null);
    }

    @Override
    public TSaltLinkPage resaveLinkPage(TSaltLinkPage record, String operator) {
        if (record == null || record.getId() == null) {
            throw new IllegalArgumentException("没点名要重挂哪一页（id 空着）");
        }
        TSaltLinkPage old = this.baseMapper.selectById(record.getId());
        if (old == null || (old.getDelFlag() != null && old.getDelFlag() == 1)) {
            throw new IllegalArgumentException("这一页在库里找不着");
        }

        // 挂接代号不换手：递上来的值跟旧号不一样，就是有人动过手，当场拒绝；
        // 一样（或没递）才放行——代号原样不动，保存不会被自己绊住。
        if (StringUtils.isNotEmpty(record.getBillNo())
                && !record.getBillNo().equals(old.getBillNo())) {
            throw new IllegalArgumentException("挂接代号归系统发、不换手，这一页保存不了");
        }
        if (StringUtils.isNotEmpty(record.getSignCode())
                && !record.getSignCode().equals(old.getSignCode())) {
            throw new IllegalArgumentException("随行签认码归系统发，这一页保存不了");
        }

        // 所对企业：交了新值按新值，没交沿用旧值。
        // 旧页所对的企业哪怕后来摘了牌，页照旧算在册、照旧能改——摘牌只拦新开页。
        Integer siteId = record.getSiteId() != null ? record.getSiteId() : old.getSiteId();
        TSaltEnt ent = loadEnt(siteId);

        int nodeNo = normalizeNodeNo(record.getNodeNo() != null ? record.getNodeNo() : old.getNodeNo());
        BigDecimal should = requireShould(record.getShouldCount() != null
                ? record.getShouldCount() : old.getShouldCount());
        BigDecimal done = normalizeDone(record.getDoneCount() != null
                ? record.getDoneCount() : old.getDoneCount());
        validateCounts(should, done);

        record.setBillNo(old.getBillNo());
        record.setSignCode(old.getSignCode());
        record.setSiteId(Integer.valueOf(ent.getId().intValue()));
        record.setSiteNo(ent.getSiteNo());
        record.setScopeRoad(pressRoad(ent.getRoadName(), nodeNo));
        record.setNodeNo(nodeNo);
        record.setShouldCount(should);
        record.setDoneCount(done);
        record.setLackCount(should.subtract(done));
        record.setStatus(old.getStatus() == null ? STATUS_PENDING : old.getStatus());
        record.setDelFlag(0);
        // 开单人始终是头回起页那位：更新不带 create_by（null 不进 SET 子句），库里的署名原样留着；
        // 重挂的人只落到 update_by 上
        if (StringUtils.isNotEmpty(operator)) {
            record.setUpdateBy(operator);
        }
        record.setUpdateTime(new Date());

        this.baseMapper.updateById(record);
        return this.baseMapper.selectById(record.getId());
    }

    // ------------------------------------------------------------------
    // 名录页一屏
    // ------------------------------------------------------------------

    @Override
    public SaltBoardResult openBoard(SaltBoardQuery query, TSysUserView operator) {
        List<TSaltLinkPage> all = scoopBoard(query, operator);

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

        SaltBoardResult result = new SaltBoardResult();
        result.setRows(pageRows);
        result.setTotal(all.size());
        result.setRegisteredCount(registered);
        result.setDoneCount(done);
        result.setHeldCount(held);
        if (operator != null) {
            result.setScopeRoad(operator.getDeptName());
        }
        return result;
    }

    /**
     * 名录屏与导出共用的同一把勺子：同路、同层、同筛选、同小数口径。
     * 列表从这把勺里切一屏，导出整勺端走——两处的行数与数目不可能对不上。
     */
    @Override
    public List<TSaltLinkPage> scoopBoard(SaltBoardQuery query, TSysUserView operator) {
        if (operator == null || StringUtils.isEmpty(operator.getDeptName())) {
            throw new IllegalArgumentException("操作人没有属地，名录页开不了");
        }

        int level = operator.getLevel();
        String scopeRoad = operator.getDeptName();

        QueryWrapper<TSaltLinkPage> w = new QueryWrapper<TSaltLinkPage>()
                .eq("del_flag", 0)
                // 页压在属地那一层底下：只露压在本层的页
                .eq("node_no", level)
                .eq("scope_road", scopeRoad);

        if (query != null) {
            if (StringUtils.isNotEmpty(query.getBillNo())) {
                w.like("bill_no", query.getBillNo().trim());
            }
            if (StringUtils.isNotEmpty(query.getSiteNo())) {
                w.like("site_no", query.getSiteNo().trim());
            }
            if (query.getStatus() != null) {
                w.eq("status", query.getStatus().intValue());
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

        List<TSaltLinkPage> all = this.baseMapper.selectList(w);
        fillEntInfo(all);
        return all;
    }

    /** 给逐页补上企业全称、类别（批量查一次总名录；摘牌企业也照补，旧页照旧在册） */
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
    public int changeEntRegisterStatus(Long id, int targetStatus, String operator) {
        if (id == null) {
            throw new IllegalArgumentException("没点名要改总名录上的哪一家");
        }
        if (targetStatus != 0 && targetStatus != ENT_DELISTED) {
            throw new IllegalArgumentException("名录情形只许 0在册 / 1已摘牌");
        }
        TSaltEnt ent = saltEntMapper.selectOne(new QueryWrapper<TSaltEnt>()
                .eq("id", id).eq("del_flag", 0));
        if (ent == null) {
            throw new IllegalArgumentException("这家企业不在总名录上");
        }
        TSaltEnt update = new TSaltEnt();
        update.setId(id);
        update.setStatus(targetStatus);
        if (StringUtils.isNotEmpty(operator)) {
            update.setUpdateBy(operator);
        }
        return saltEntMapper.updateById(update);
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
        // 守边界：压页不能再推，不允许跳态
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

    /** 人手递了系统发的码子，不静默读没：点名叫出，整页不进库 */
    private void rejectHandedCode(String code, String column) {
        if (StringUtils.isNotEmpty(code)) {
            throw new IllegalArgumentException(column + "归系统发，人手填的值不收，这一页起不来");
        }
    }

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

    private BigDecimal requireShould(BigDecimal should) {
        if (should == null) {
            throw new IllegalArgumentException("应挂品种数那一栏空着递，这一页起不来");
        }
        if (should.signum() < 0) {
            throw new IllegalArgumentException("应挂品种数那一栏递进来是负数，这一页起不来");
        }
        // 档案口径两位：写几位来都折成两位（四舍五入），不许五位照存
        return should.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal normalizeDone(BigDecimal done) {
        if (done == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if (done.signum() < 0) {
            throw new IllegalArgumentException("已挂品种数那一栏递进来是负数，进不了库");
        }
        return done.setScale(2, RoundingMode.HALF_UP);
    }

    private void validateCounts(BigDecimal should, BigDecimal done) {
        if (done.compareTo(should) > 0) {
            // 同一毛病从两面都说得通：应挂低过已挂 / 已挂高过应挂，回执把两栏都点出来
            throw new IllegalArgumentException(
                    "已挂品种数(" + done.toPlainString() + ")高过应挂品种数("
                            + should.toPlainString() + ")，进不了库");
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
