package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TSaltRecordFlowMapper;
import com.fc.v2.model.auto.TSaltRecordFlow;
import com.fc.v2.service.ITSaltRecordFlowService;
import com.fc.v2.util.StringUtils;

/**
 * 跨省经营备案单 Service业务层处理（state-machine 形状：单据流转）
 *
 * 步次 0递单受理/1材料核验/2生效确认/3缴回封卷；情形 0未起 1在办 2已封卷。
 * 推进/回退与挂接页是同一套规矩：一档一档挪，不许跳态，两端守界——
 * 不会一处一次 +2、另一处一次 +1。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TSaltRecordFlowServiceImpl implements ITSaltRecordFlowService {

    /** 最后一档：缴回封卷 */
    private static final int STAGE_FIRST = 0;
    private static final int STAGE_FINAL = 3;

    /** 备案单走到的那一步 0未起 1在办 2已封卷 */
    private static final int STATUS_NOT_STARTED = 0;
    private static final int STATUS_ACTIVE = 1;
    private static final int STATUS_TERMINAL = 2;

    @javax.annotation.Resource
    private TSaltRecordFlowMapper saltRecordFlowMapper;

    @Override
    public TSaltRecordFlow selectTSaltRecordFlowById(Long id) {
        return this.saltRecordFlowMapper.selectOne(new QueryWrapper<TSaltRecordFlow>()
                .eq("id", id).eq("del_flag", 0));
    }

    @Override
    public List<TSaltRecordFlow> selectTSaltRecordFlowList(QueryWrapper<TSaltRecordFlow> queryWrapper) {
        QueryWrapper<TSaltRecordFlow> w = queryWrapper == null ? new QueryWrapper<TSaltRecordFlow>() : queryWrapper;
        return this.saltRecordFlowMapper.selectList(w.eq("del_flag", 0));
    }

    @Override
    public TSaltRecordFlow advance(Long id, String remark) {
        TSaltRecordFlow r = loadLive(id);
        if (r == null) {
            return null;
        }
        int st = r.getStage() == null ? STAGE_FIRST : r.getStage();
        int status = r.getStatus() == null ? STATUS_NOT_STARTED : r.getStatus();
        // 已封卷不许再推；已在最后一档也无处可推——守边界，不跳态
        if (status == STATUS_TERMINAL || st >= STAGE_FINAL) {
            return null;
        }
        int next = st + 1;
        r.setStage(next);
        // 挪到缴回封卷这一档即封卷；其余档都算在办
        r.setStatus(next >= STAGE_FINAL ? STATUS_TERMINAL : STATUS_ACTIVE);
        r.setLastAction(StringUtils.isNotEmpty(remark) ? remark.trim() : "推进");
        this.saltRecordFlowMapper.updateById(r);
        return r;
    }

    @Override
    public TSaltRecordFlow rollback(Long id, String remark) {
        TSaltRecordFlow r = loadLive(id);
        if (r == null) {
            return null;
        }
        if (r.getStage() == null) {
            return null;
        }
        int st = r.getStage();
        // 第一档无处可退——守边界，不一把归零
        if (st <= STAGE_FIRST) {
            return null;
        }
        int prev = st - 1;
        r.setStage(prev);
        // 退回后仍是受理中的单子（哪怕退到递单受理），算在办，不抹成"未起"
        r.setStatus(STATUS_ACTIVE);
        r.setLastAction(StringUtils.isNotEmpty(remark) ? remark.trim() : "回退");
        this.saltRecordFlowMapper.updateById(r);
        return r;
    }

    @Override
    public boolean updateContent(Long id, String remark) {
        TSaltRecordFlow r = loadLive(id);
        if (r == null) {
            return false;
        }
        // 已封卷不得再改
        if (r.getStatus() != null && r.getStatus() == STATUS_TERMINAL) {
            return false;
        }
        r.setContent(remark);
        return this.saltRecordFlowMapper.updateById(r) > 0;
    }

    @Override
    public boolean remove(Long id) {
        TSaltRecordFlow r = loadLive(id);
        if (r == null) {
            return false;
        }
        // 已封卷不得删除
        if (r.getStatus() != null && r.getStatus() == STATUS_TERMINAL) {
            return false;
        }
        TSaltRecordFlow tomb = new TSaltRecordFlow();
        tomb.setId(id);
        tomb.setDelFlag(1);
        return this.saltRecordFlowMapper.updateById(tomb) > 0;
    }

    private TSaltRecordFlow loadLive(Long id) {
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
