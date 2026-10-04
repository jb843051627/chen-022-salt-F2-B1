package com.fc.v2.service;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.model.auto.TSaltRecordFlow;

/**
 * 跨省经营备案单 Service接口（state-machine 形状：单据流转，不提供增删改查全套）
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITSaltRecordFlowService {

    /** 按主键查询单据 */
    TSaltRecordFlow selectTSaltRecordFlowById(Long id);

    /** 列表查询（流转台账） */
    List<TSaltRecordFlow> selectTSaltRecordFlowList(QueryWrapper<TSaltRecordFlow> queryWrapper);

    /**
     * 登一笔备案单：挂接代号（biz_no）必须对得上挂接簿上在册的一页，
     * 从 0档（递单受理）、未起态走起。代号对不上档案不收。
     */
    TSaltRecordFlow register(String bizNo);

    /** 推进一档：返回更新后的单据；被拒返回 null */
    TSaltRecordFlow advance(Long id, String remark);

    /** 回退一档：返回更新后的单据；被拒返回 null */
    TSaltRecordFlow rollback(Long id, String remark);

    /** 修改内容：已办结不得再改，被拒返回 false */
    boolean updateContent(Long id, String remark);

    /** 删除单据：已办结不得删除，被拒返回 false */
    boolean remove(Long id);
}
