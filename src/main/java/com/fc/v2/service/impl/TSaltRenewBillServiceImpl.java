package com.fc.v2.service.impl;

import java.util.Date;

import org.springframework.stereotype.Service;

import com.fc.v2.mapper.auto.TSaltRenewBillMapper;
import com.fc.v2.model.auto.TSaltRenewBill;
import com.fc.v2.service.ITSaltRenewBillService;
import com.fc.v2.util.StringUtils;

/**
 * 延续换证核签单 Service业务层处理（approval-chain 形状：多阶段签批）
 *
 * 三档 0县初核/1市复审/2省核准，同档算齐办法 signMode：0一名即可、1两名须同判。
 * 每落一印 signCount+1，达到本档应落人数才挪下一档并清零重计；
 * 省核准档签齐即办结(1)，办结后不许再批；否决落"已挪回"(2)，
 * 挪回可退回上一环节继续在核(0)。两个口子（推进/退回）同一套守界，没有跳档。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TSaltRenewBillServiceImpl implements ITSaltRenewBillService {

    /** 最后一档：省核准 */
    private static final int NODE_FINAL = 2;

    /** 核签情形 0在核 1已办结 2已挪回 */
    private static final int STATUS_RUNNING = 0;
    private static final int STATUS_PASS = 1;
    private static final int STATUS_VETO = 2;

    /** signMode：一名即可 */
    private static final int MODE_OR = 0;
    /** signMode：两名须同判 */
    private static final int MODE_AND = 1;

    @javax.annotation.Resource
    private TSaltRenewBillMapper saltRenewBillMapper;

    @Override
    public TSaltRenewBill selectTSaltRenewBillById(Long id) {
        return this.saltRenewBillMapper.selectById(id);
    }

    @Override
    public TSaltRenewBill approve(Long id, String approver, String comment) {
        if (id == null || StringUtils.isEmpty(approver)) {
            return null;
        }
        TSaltRenewBill r = loadLive(id);
        if (r == null) {
            return null;
        }
        // 办结了不许再批；已被否决的要先走退回，不许在挪回状态上直接签
        if (r.getStatus() != null && r.getStatus() != STATUS_RUNNING) {
            return null;
        }

        int node = r.getNodeNo() == null ? 0 : r.getNodeNo();
        if (node > NODE_FINAL) {
            return null;
        }
        int signed = r.getSignCount() == null ? 0 : r.getSignCount();
        int need = needCount(r);
        if (signed >= need) {
            // 本档已签齐、尚未挪档的边界状态：不重复计印
            return null;
        }
        signed++;
        r.setSignCount(signed);
        if (StringUtils.isNotEmpty(comment)) {
            r.setRemark(comment.trim());
        }
        if (signed < need) {
            // 本档印还没落齐，留在原档继续在核
            r.setStatus(STATUS_RUNNING);
            this.saltRenewBillMapper.updateById(r);
            return r;
        }

        // 本档签齐：省核准是最后一档，签齐即办结；其余挪下一档、印数清零重计
        if (node >= NODE_FINAL) {
            r.setNodeNo(NODE_FINAL);
            r.setStatus(STATUS_PASS);
        } else {
            r.setNodeNo(node + 1);
            r.setSignCount(0);
            r.setStatus(STATUS_RUNNING);
        }
        r.setUpdateTime(new Date());
        this.saltRenewBillMapper.updateById(r);
        return r;
    }

    @Override
    public TSaltRenewBill reject(Long id, String approver, String comment) {
        if (id == null || StringUtils.isEmpty(approver)) {
            return null;
        }
        TSaltRenewBill r = loadLive(id);
        if (r == null) {
            return null;
        }
        // 已办结不能再否决；已经挪回的不重复落状态
        if (r.getStatus() != null && (r.getStatus() == STATUS_PASS || r.getStatus() == STATUS_VETO)) {
            return null;
        }
        r.setStatus(STATUS_VETO);
        if (StringUtils.isNotEmpty(comment)) {
            r.setRemark(comment.trim());
        }
        r.setUpdateTime(new Date());
        this.saltRenewBillMapper.updateById(r);
        return r;
    }

    @Override
    public TSaltRenewBill rollback(Long id, String comment) {
        TSaltRenewBill r = loadLive(id);
        if (r == null) {
            return null;
        }
        int node = r.getNodeNo() == null ? 0 : r.getNodeNo();
        // 县初核是头档，无处可退；办结的单子也不退
        if (node <= 0 || (r.getStatus() != null && r.getStatus() == STATUS_PASS)) {
            return null;
        }
        r.setNodeNo(node - 1);
        // 退回上一环节重新在核，上一档的落印作废、从头签
        r.setSignCount(0);
        r.setStatus(STATUS_RUNNING);
        if (StringUtils.isNotEmpty(comment)) {
            r.setRemark(comment.trim());
        }
        r.setUpdateTime(new Date());
        this.saltRenewBillMapper.updateById(r);
        return r;
    }

    /** 本档应落人数：needCount 有值按它；没有按 signMode，一名即可=1，两名同判=2 */
    private int needCount(TSaltRenewBill r) {
        if (r.getNeedCount() != null && r.getNeedCount() > 0) {
            return r.getNeedCount();
        }
        return (r.getSignMode() != null && r.getSignMode() == MODE_AND) ? 2 : 1;
    }

    private TSaltRenewBill loadLive(Long id) {
        TSaltRenewBill r = this.saltRenewBillMapper.selectById(id);
        if (r == null || (r.getDelFlag() != null && r.getDelFlag() == 1)) {
            return null;
        }
        return r;
    }
}
