package com.fc.v2.model.custom;

import java.io.Serializable;

/**
 * 挂接簿名录页一屏的寻页项（挂接代号 / 企业代号 / 企业类别），
 * 三项可给可不给，给整段或只咬中几个字都作寻着。
 *
 * @author fuce
 */
public class SaltBoardQuery implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 第几页，从1起 */
    private int page = 1;

    /** 一屏摆多少条（处里定） */
    private int limit = 10;

    /** 挂接代号（模糊） */
    private String billNo;

    /** 企业代号（模糊） */
    private String siteNo;

    /** 企业类别（模糊） */
    private String siteType;

    /** 挂接进展 0待挂 1已挂讫 2压页；不给不筛 */
    private Integer status;

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public String getBillNo() {
        return billNo;
    }

    public void setBillNo(String billNo) {
        this.billNo = billNo;
    }

    public String getSiteNo() {
        return siteNo;
    }

    public void setSiteNo(String siteNo) {
        this.siteNo = siteNo;
    }

    public String getSiteType() {
        return siteType;
    }

    public void setSiteType(String siteType) {
        this.siteType = siteType;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
