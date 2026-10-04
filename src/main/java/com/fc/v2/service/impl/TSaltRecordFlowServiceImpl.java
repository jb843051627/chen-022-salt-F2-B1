package com.fc.v2.service.impl;

import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TSaltRecordFlowMapper;
import com.fc.v2.model.auto.TSaltRecordFlow;
import com.fc.v2.service.ITSaltLinkPageService;
import com.fc.v2.service.ITSaltRecordFlowService;
import com.fc.v2.util.StringUtils;

/**
 * 跨省经营备案单 Service业务层处理（state-machine 形状：单据流转）
 *
 * 步次 0..3：递单受理/材料核验/生效确认/缴回封卷；一次只许挪一档，到顶置已封卷。
 * 与企业品种挂接簿的联动只在这两个口子上发生：
 * 推进到缴回封卷 → 挂接页已挂 +1；从已封卷挪回 → 已挂 -1。
 * 应挂品种数归档案，任何一次领单/流转都不碰它。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TSaltRecordFlowServiceImpl implements ITSaltRecordFlowService {

    /** 步次上限 0..3 */
    private static final int MAX_STAGE = 3;
    /** 0未起 1在办 2已封卷 */
    private static final int STATUS_UNSTART = 0;
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_TERMINAL = 2;

    @javax.annotation.Resource
    private TSaltRecordFlowMapper saltRecordFlowMapper;

    private final ITSaltLinkPageService saltLinkPageService;

    public TSaltRecordFlowServiceImpl(ITSaltLinkPageService saltLinkPageService) {
        this.saltLinkPageService = saltLinkPageService;
    }

    @Override
    public TSaltRecordFlow selectTSaltRecordFlowById(Long id) {
        return this.saltRecordFlowMapper.selectById(id);
    }

    @Override
    public List<TSaltRecordFlow> selectTSaltRecordFlowList(QueryWrapper<TSaltRecordFlow> queryWrapper) {
        return this.saltRecordFlowMapper.selectList(queryWrapper);
    }

    @Override
    public TSaltRecordFlow register(String bizNo) {
        if (StringUtils.isEmpty(bizNo)) {
            throw new IllegalArgumentException("挂接代号那一栏空着，这一笔登不了");
        }
        String no = bizNo.trim();
        // 代号必须对得上挂接簿档案：人手编一个、已经沉底的，一律不收
        if (!saltLinkPageService.billNoInRegister(no)) {
            throw new IllegalArgumentException("挂接代号 " + no + " 对不上挂接簿档案，登不了");
        }
        // 同一个在册代号只许有一笔在流转：前后录两遍的第二笔挡下
        Integer dup = this.saltRecordFlowMapper.selectCount(new QueryWrapper<TSaltRecordFlow>()
                .eq("biz_no", no)
                .ne("status", STATUS_TERMINAL)
                .eq("del_flag", 0));
        if (dup != null && dup > 0) {
            throw new IllegalArgumentException("挂接代号 " + no + " 已有一笔在流转，不能再登一遍");
        }
        TSaltRecordFlow r = new TSaltRecordFlow();
        r.setBizNo(no);
        r.setStage(0);
        r.setStatus(STATUS_UNSTART);
        r.setDelFlag(0);
        this.saltRecordFlowMapper.insert(r);
        return r;
    }

    @Override
    public TSaltRecordFlow advance(Long id, String remark) {
        TSaltRecordFlow r = loadAlive(id);
        if (r == null) {
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        // 已封卷的单子不能再推，也不许跳档
        int status = r.getStatus() == null ? STATUS_UNSTART : r.getStatus();
        if (status == STATUS_TERMINAL || st >= MAX_STAGE) {
            return null;
        }
        int next = st + 1;
        int nextStatus = next >= MAX_STAGE ? STATUS_TERMINAL : STATUS_ACTIVE;
        TSaltRecordFlow update = new TSaltRecordFlow();
        update.setId(id);
        update.setStage(next);
        update.setStatus(nextStatus);
        update.setLastAction(StringUtils.isNotEmpty(remark) ? remark : "推进一档");
        update.setUpdateTime(new Date());
        this.saltRecordFlowMapper.updateById(update);

        // 跨模块联动：缴回封卷这一档，挂接页已挂挪一格；应挂一律不碰
        if (nextStatus == STATUS_TERMINAL) {
            saltLinkPageService.applyLinkDone(r.getBizNo(), 1);
        }
        return this.saltRecordFlowMapper.selectById(id);
    }

    @Override
    public TSaltRecordFlow rollback(Long id, String remark) {
        TSaltRecordFlow r = loadAlive(id);
        if (r == null) {
            return null;
        }
        int st = r.getStage() == null ? 0 : r.getStage();
        // 未起/还在受理档，无处可退
        if (st <= 0) {
            return null;
        }
        int prev = st - 1;
        TSaltRecordFlow update = new TSaltRecordFlow();
        update.setId(id);
        update.setStage(prev);
        update.setStatus(STATUS_ACTIVE);
        update.setLastAction(StringUtils.isNotEmpty(remark) ? remark : "退回一档");
        update.setUpdateTime(new Date());
        this.saltRecordFlowMapper.updateById(update);

        // 从封卷口挪回，把联动挪过去的那格已挂退回来
        int status = r.getStatus() == null ? STATUS_UNSTART : r.getStatus();
        if (status == STATUS_TERMINAL || st >= MAX_STAGE) {
            saltLinkPageService.applyLinkDone(r.getBizNo(), -1);
        }
        return this.saltRecordFlowMapper.selectById(id);
    }

    @Override
    public boolean updateContent(Long id, String remark) {
        TSaltRecordFlow r = loadAlive(id);
        if (r == null) {
            return false;
        }
        // 已封卷的单子定案，内容不得再改
        if (r.getStatus() != null && r.getStatus() == STATUS_TERMINAL) {
            return false;
        }
        TSaltRecordFlow update = new TSaltRecordFlow();
        update.setId(id);
        update.setContent(remark);
        update.setUpdateTime(new Date());
        return this.saltRecordFlowMapper.updateById(update) > 0;
    }

    @Override
    public boolean remove(Long id) {
        TSaltRecordFlow r = loadAlive(id);
        if (r == null) {
            return false;
        }
        // 已封卷的单子要留档，不得删
        if (r.getStatus() != null && r.getStatus() == STATUS_TERMINAL) {
            return false;
        }
        TSaltRecordFlow update = new TSaltRecordFlow();
        update.setId(id);
        update.setDelFlag(1);
        update.setUpdateTime(new Date());
        return this.saltRecordFlowMapper.updateById(update) > 0;
    }

    /** 取在册、未删除的单子；找不着/已沉底返回 null */
    private TSaltRecordFlow loadAlive(Long id) {
        if (id == null) {
            return null;
        }
        TSaltRecordFlow r = this.saltRecordFlowMapper.selectById(id);
        if (r == null || (r.getDelFlag() != null && r.getDelFlag() == 1)) {
            return null;
        }
        return r;
    }
}
