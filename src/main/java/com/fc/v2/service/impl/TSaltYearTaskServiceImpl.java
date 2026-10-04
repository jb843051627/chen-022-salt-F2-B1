package com.fc.v2.service.impl;

import java.util.Date;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TSaltYearTaskMapper;
import com.fc.v2.model.auto.TSaltYearTask;
import com.fc.v2.service.ITSaltYearTaskService;

/**
 * 年检到期催办单 Service业务层处理（scheduling-job 形状：周期执行）
 *
 * 可开口窗口：每条的 amount 是"最可提前几日开口"，窗口起点 = 应开口止点(dueAt) 前 amount 日。
 * 只有"在窗口内、未派过、未删除"的条目才算到期；派出后立刻置成已派出，
 * 同一周期再跑、以后每回再跑都不会把同一条重复算进去。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TSaltYearTaskServiceImpl implements ITSaltYearTaskService {

    /** 条目情形 0待派 1已派出 2分不出 */
    private static final int STATUS_WAIT = 0;
    private static final int STATUS_DONE = 1;

    private static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;

    @javax.annotation.Resource
    private TSaltYearTaskMapper saltYearTaskMapper;

    @Override
    public TSaltYearTask selectTSaltYearTaskById(Long id) {
        return this.saltYearTaskMapper.selectOne(new QueryWrapper<TSaltYearTask>()
                .eq("id", id).eq("del_flag", 0));
    }

    @Override
    public List<TSaltYearTask> listDue(Date at) {
        if (at == null) {
            return new java.util.ArrayList<TSaltYearTask>();
        }
        // 只捞待派、未删的；窗口到不到逐条目按各自 amount 判
        List<TSaltYearTask> all = this.saltYearTaskMapper.selectList(new QueryWrapper<TSaltYearTask>()
                .eq("del_flag", 0).eq("status", STATUS_WAIT));
        List<TSaltYearTask> due = new java.util.ArrayList<TSaltYearTask>();
        for (TSaltYearTask r : all) {
            if (r.getDueAt() == null) {
                continue;
            }
            if (inWindow(r, at)) {
                due.add(r);
            }
        }
        return due;
    }

    @Override
    public int runOnce(Date at) {
        List<TSaltYearTask> due = listDue(at);
        if (due.isEmpty()) {
            // 空批不再 get(0) 崩，安静返回 0
            return 0;
        }
        int ok = 0;
        for (TSaltYearTask r : due) {
            try {
                TSaltYearTask done = new TSaltYearTask();
                done.setId(r.getId());
                // 派出即回写已派出：下回跑不重复派、不重复计数
                done.setStatus(STATUS_DONE);
                if (this.saltYearTaskMapper.updateById(done) > 0) {
                    ok++;
                }
            } catch (RuntimeException skip) {
                // 单条失败跳过继续
            }
        }
        return ok;
    }

    /** at 是否进入该条的可开口窗口：dueAt 前 amount 日（含）起，到期之后仍催 */
    private boolean inWindow(TSaltYearTask r, Date at) {
        long now = at.getTime();
        long dueAt = r.getDueAt().getTime();
        long advanceDays = r.getAmount() == null ? 0L : Math.max(0L, r.getAmount().longValue());
        long from = dueAt - advanceDays * ONE_DAY_MS;
        return now >= from;
    }
}
