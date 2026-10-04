package com.fc.v2.model.custom;

import java.io.Serializable;

/**
 * 挂接簿页起页/重挂表单。
 * 数目两栏按原文收（字符串），由接口层统一折数——
 * 空着、负数、不是数都能点名叫出毛病；小数照收但按档案口径折成两位（四舍五入），
 * 不依赖 Spring 绑定那一种报错。
 * 挂接代号、随行签认码两栏不在表单上：人手写来一律不收。
 *
 * @author fuce
 */
public class SaltLinkPageForm implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主键（重挂时给） */
    private String id;

    /** 所对定点企业（企业总名录代号主键） */
    private String siteId;

    /** 适用区域层级序 0省 1市 2县 */
    private String nodeNo;

    /** 应挂品种数（原文） */
    private String shouldCount;

    /** 已挂品种数（原文） */
    private String doneCount;

    /** 备注 */
    private String remark;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSiteId() {
        return siteId;
    }

    public void setSiteId(String siteId) {
        this.siteId = siteId;
    }

    public String getNodeNo() {
        return nodeNo;
    }

    public void setNodeNo(String nodeNo) {
        this.nodeNo = nodeNo;
    }

    public String getShouldCount() {
        return shouldCount;
    }

    public void setShouldCount(String shouldCount) {
        this.shouldCount = shouldCount;
    }

    public String getDoneCount() {
        return doneCount;
    }

    public void setDoneCount(String doneCount) {
        this.doneCount = doneCount;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
