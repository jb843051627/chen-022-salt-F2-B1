package com.fc.v2.model.custom;

import com.fc.v2.model.auto.TSaltLinkPage;

import java.io.Serializable;
import java.util.List;

/**
 * 挂接簿名录页一屏：逐页行册与三栏（在册页数 / 已挂讫 / 压页）。
 * 三栏与本屏逐行出自同一次算——先圈定同一批行，再就这批行点数，
 * 不另查、不拿旁处的行凑数。
 *
 * @author fuce
 */
public class SaltBoardResult implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 本屏行册（库里实有、且该归本层露的页） */
    private List<TSaltLinkPage> rows;

    /** 本屏条数 */
    private long total;

    /** 在册页数（同批行点数） */
    private int registeredCount;

    /** 已挂讫页数（同批行点数） */
    private int doneCount;

    /** 压页页数（同批行点数） */
    private int heldCount;

    /** 本屏按的那一条归属地路 */
    private String scopeRoad;

    public List<TSaltLinkPage> getRows() {
        return rows;
    }

    public void setRows(List<TSaltLinkPage> rows) {
        this.rows = rows;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public int getRegisteredCount() {
        return registeredCount;
    }

    public void setRegisteredCount(int registeredCount) {
        this.registeredCount = registeredCount;
    }

    public int getDoneCount() {
        return doneCount;
    }

    public void setDoneCount(int doneCount) {
        this.doneCount = doneCount;
    }

    public int getHeldCount() {
        return heldCount;
    }

    public void setHeldCount(int heldCount) {
        this.heldCount = heldCount;
    }

    public String getScopeRoad() {
        return scopeRoad;
    }

    public void setScopeRoad(String scopeRoad) {
        this.scopeRoad = scopeRoad;
    }
}
