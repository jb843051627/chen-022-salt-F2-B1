package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TSaltIodLineMapper;
import com.fc.v2.model.auto.TSaltIodLine;
import com.fc.v2.service.ITSaltIodLineService;

/**
 * 碘含量分档判定线 Service业务层处理（rule-eval 形状）
 *
 * "在场"的口径全模块一致：未删(del_flag=0)、未停用(status=0)、
 * 立线之日 effStart <= at < 让位之日 effEnd（effEnd 空着表示至今有效）。
 * 多条同时在场按让位顺位 priority 降序，并列按 ruleCode 降序——
 * 定位、列表、角标、可用判定走同一套，不会这处拦那处放。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TSaltIodLineServiceImpl implements ITSaltIodLineService {

    /** 线的情形 0在场 1已停用 */
    private static final int LINE_ACTIVE = 0;

    /** 优先级降序；并列按 ruleCode 降序（确定性） */
    private static final Comparator<TSaltIodLine> PRIORITY_THEN_CODE_DESC = new Comparator<TSaltIodLine>() {
        @Override
        public int compare(TSaltIodLine a, TSaltIodLine b) {
            int pa = a.getPriority() == null ? 0 : a.getPriority();
            int pb = b.getPriority() == null ? 0 : b.getPriority();
            if (pa != pb) {
                return pb - pa;
            }
            String ca = a.getRuleCode() == null ? "" : a.getRuleCode();
            String cb = b.getRuleCode() == null ? "" : b.getRuleCode();
            return cb.compareTo(ca);
        }
    };

    @javax.annotation.Resource
    private TSaltIodLineMapper saltIodLineMapper;

    @Override
    public TSaltIodLine selectTSaltIodLineById(Long id) {
        return this.saltIodLineMapper.selectById(id);
    }

    @Override
    public List<TSaltIodLine> listAvailable(Date at) {
        if (at == null) {
            return new java.util.ArrayList<TSaltIodLine>();
        }
        List<TSaltIodLine> all = this.saltIodLineMapper.selectList(new QueryWrapper<TSaltIodLine>()
                .eq("del_flag", 0).eq("status", LINE_ACTIVE));
        List<TSaltIodLine> avail = new java.util.ArrayList<TSaltIodLine>();
        for (TSaltIodLine r : all) {
            if (isActiveLine(r) && effectiveAt(r, at)) {
                avail.add(r);
            }
        }
        avail.sort(PRIORITY_THEN_CODE_DESC);
        return avail;
    }

    @Override
    public int evaluate(String ruleCode, BigDecimal input, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || input == null || at == null) {
            return 0;
        }
        TSaltIodLine rule = findRule(ruleCode, at);
        if (rule == null) {
            return 0;
        }
        return levelOf(rule, input);
    }

    @Override
    public int evaluateTop(BigDecimal input, Date at) {
        if (input == null || at == null) {
            return 0;
        }
        if (input.compareTo(BigDecimal.ZERO) < 0
                || input.compareTo(new BigDecimal("100")) > 0) {
            return 0;
        }
        List<TSaltIodLine> avail = listAvailable(at);
        if (avail.isEmpty()) {
            return 0;
        }
        // 排序后的头一条就是优先级最高的在场线，不再随便取库里的第一行
        return levelOf(avail.get(0), input);
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        TSaltIodLine r = this.saltIodLineMapper.selectById(id);
        if (r == null || (r.getDelFlag() != null && r.getDelFlag() == 1)) {
            return false;
        }
        // 停用的线、不在生效窗内的线，旁路（导入/报表）也不许用
        return (r.getStatus() == null || r.getStatus() == LINE_ACTIVE) && effectiveAt(r, at);
    }

    @Override
    public int countAvailable(Date at) {
        if (at == null) {
            return 0;
        }
        return listAvailable(at).size();
    }

    private TSaltIodLine findRule(String ruleCode, Date at) {
        List<TSaltIodLine> hit = this.saltIodLineMapper.selectList(new QueryWrapper<TSaltIodLine>()
                .eq("del_flag", 0).eq("status", LINE_ACTIVE).eq("rule_code", ruleCode));
        TSaltIodLine active = null;
        for (TSaltIodLine r : hit) {
            if (isActiveLine(r) && effectiveAt(r, at)) {
                // 同一代号在 at 时刻理论上只该有一版在场；有多版时取让位日最晚的
                if (active == null || laterEffEnd(r, active)) {
                    active = r;
                }
            }
        }
        return active;
    }

    /** 在场：未删且未停用（SQL 已钉一遍，内存再兜一道，口径不押在单一防线上） */
    private boolean isActiveLine(TSaltIodLine r) {
        return (r.getDelFlag() == null || r.getDelFlag() == 0)
                && (r.getStatus() == null || r.getStatus() == LINE_ACTIVE);
    }

    /** a 的让位日是否晚于 b（null 让位日表示至今有效，视为最晚） */
    private boolean laterEffEnd(TSaltIodLine a, TSaltIodLine b) {
        if (a.getEffEnd() == null) {
            return b.getEffEnd() != null;
        }
        if (b.getEffEnd() == null) {
            return false;
        }
        return a.getEffEnd().after(b.getEffEnd());
    }

    /** at 是否落在该线生效区间：effStart <= at < effEnd；两端可空，空端不设限 */
    private boolean effectiveAt(TSaltIodLine r, Date at) {
        if (r.getEffStart() != null && at.before(r.getEffStart())) {
            return false;
        }
        if (r.getEffEnd() != null && !at.before(r.getEffEnd())) {
            return false;
        }
        return true;
    }

    /** 分档：等于上限取高一档 */
    private int levelOf(TSaltIodLine rule, BigDecimal input) {
        if (rule.getTh1Max() != null && input.compareTo(rule.getTh1Max()) <= 0) {
            return 1;
        }
        if (rule.getTh2Max() != null && input.compareTo(rule.getTh2Max()) <= 0) {
            return 2;
        }
        if (rule.getTh3Max() != null && input.compareTo(rule.getTh3Max()) <= 0) {
            return 3;
        }
        return 4;
    }
}
