package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TSaltEntMapper;
import com.fc.v2.mapper.auto.TSaltSpotRowMapper;
import com.fc.v2.model.auto.TSaltEnt;
import com.fc.v2.model.auto.TSaltSpotRow;
import com.fc.v2.service.ITSaltSpotRowService;
import com.fc.v2.util.StringUtils;

/**
 * 抽检结果报送核收行 Service业务层处理（batch-process 形状：整批提交）
 *
 * 口径：
 * - 空批回 0；超单批上限回 -1 且整批一笔不入库；
 * - 同一批次重复提交幂等：已收下过的批次不重复入库，回已收行数；
 * - 逐行校验：企业代号必须对得上企业总名录（在档、未删），件数为正、不合格不超送检，
 *   数目按档案 decimal(12,2) 折两位；
 * - 合法行收下（保留原始行次），非法行只记失败明细，上一批留下的失败明细重报时清掉重记。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TSaltSpotRowServiceImpl implements ITSaltSpotRowService {

    private static final int MAX_ROWS = 500;
    /** 行落地情形 0未勾 1已收下 2已退回 */
    private static final int STATUS_FAIL = 2;
    private static final int STATUS_OK = 1;

    @javax.annotation.Resource
    private TSaltSpotRowMapper saltSpotRowMapper;

    @javax.annotation.Resource
    private TSaltEntMapper saltEntMapper;

    @Override
    public TSaltSpotRow selectTSaltSpotRowById(Long id) {
        return this.saltSpotRowMapper.selectById(id);
    }

    @Override
    public int submitBatch(String batchNo, List<TSaltSpotRow> rows) {
        if (StringUtils.isEmpty(batchNo)) {
            return 0;
        }
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        if (rows.size() > MAX_ROWS) {
            // 超上限整批不入库，一条都不先落
            return -1;
        }

        // 幂等：这一批已收下过，重复提交不再入库，把已收行数原样回给屏上
        Integer accepted = saltSpotRowMapper.selectCount(new QueryWrapper<TSaltSpotRow>()
                .eq("batch_no", batchNo).eq("status", STATUS_OK));
        if (accepted != null && accepted > 0) {
            return accepted;
        }

        // 重报时先把上一轮的失败明细撤掉，免得同一批的老错行越积越多
        saltSpotRowMapper.delete(new QueryWrapper<TSaltSpotRow>()
                .eq("batch_no", batchNo).eq("status", STATUS_FAIL));

        // 企业代号对档案：只认总名录上在档（未删）的企业，人手瞎填一个不收
        Set<String> archiveNos = loadArchiveSiteNos(rows);

        List<TSaltSpotRow> valid = new ArrayList<TSaltSpotRow>();
        List<TSaltSpotRow> errors = new ArrayList<TSaltSpotRow>();
        for (int i = 0; i < rows.size(); i++) {
            TSaltSpotRow r = rows.get(i);
            int rowNo = i + 1;
            r.setBatchNo(batchNo);
            r.setRowNo(rowNo);
            if (!isRowValid(r, archiveNos)) {
                r.setStatus(STATUS_FAIL);
                errors.add(r);
                continue;
            }
            r.setQty(scale2(r.getQty()));
            if (r.getHoursNum() != null) {
                r.setHoursNum(scale2(r.getHoursNum()));
            }
            r.setStatus(STATUS_OK);
            valid.add(r);
        }

        for (TSaltSpotRow bad : errors) {
            this.saltSpotRowMapper.insert(bad);
        }
        int ok = 0;
        for (TSaltSpotRow good : valid) {
            this.saltSpotRowMapper.insert(good);
            ok++;
        }
        return ok;
    }

    @Override
    public List<TSaltSpotRow> listErrors(String batchNo) {
        if (StringUtils.isEmpty(batchNo)) {
            return new ArrayList<TSaltSpotRow>();
        }
        return this.saltSpotRowMapper.selectList(new QueryWrapper<TSaltSpotRow>()
                .eq("batch_no", batchNo).eq("status", STATUS_FAIL)
                .orderByAsc("row_no"));
    }

    private boolean isRowValid(TSaltSpotRow r, Set<String> archiveNos) {
        if (r == null) {
            return false;
        }
        if (StringUtils.isEmpty(r.getItemCode()) || !archiveNos.contains(r.getItemCode().trim())) {
            // 对不上企业总名录的代号，一律不收
            return false;
        }
        if (r.getQty() == null || r.getQty().compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        if (r.getHoursNum() != null && r.getHoursNum().compareTo(BigDecimal.ZERO) < 0) {
            return false;
        }
        // 不合格件数不能高过送检件数
        if (r.getHoursNum() != null && r.getQty().compareTo(r.getHoursNum()) > 0) {
            return false;
        }
        return true;
    }

    private Set<String> loadArchiveSiteNos(List<TSaltSpotRow> rows) {
        Set<String> asked = new HashSet<String>();
        for (TSaltSpotRow r : rows) {
            if (r != null && StringUtils.isNotEmpty(r.getItemCode())) {
                asked.add(r.getItemCode().trim());
            }
        }
        if (asked.isEmpty()) {
            return new HashSet<String>();
        }
        List<TSaltEnt> ents = saltEntMapper.selectList(new QueryWrapper<TSaltEnt>()
                .select("site_no").eq("del_flag", 0).in("site_no", asked));
        Set<String> nos = new HashSet<String>();
        for (TSaltEnt e : ents) {
            if (StringUtils.isNotEmpty(e.getSiteNo())) {
                nos.add(e.getSiteNo().trim());
            }
        }
        return nos;
    }

    private BigDecimal scale2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
