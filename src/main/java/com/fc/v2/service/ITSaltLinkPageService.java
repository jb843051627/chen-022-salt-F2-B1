package com.fc.v2.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fc.v2.model.auto.TSaltEnt;
import com.fc.v2.model.auto.TSaltLinkPage;
import com.fc.v2.model.custom.SaltBoardQuery;
import com.fc.v2.model.custom.SaltBoardResult;

import java.util.List;

/**
 * 企业品种挂接簿页 Service接口。
 * 页合不合、欠挂折几、按属地该露哪几页、名录页三栏三个数，
 * 四样都在本服务的保存/开屏方法里定夺，接口层与屏上不另摆判路。
 *
 * @author fuce
 * @date 2026-09-12
 */
public interface ITSaltLinkPageService {

    /** 按主键查询 */
    TSaltLinkPage selectTSaltLinkPageById(Long id);

    /** 按条件查询列表（分页由调用方统一处理） */
    List<TSaltLinkPage> selectTSaltLinkPageList(Wrapper<TSaltLinkPage> queryWrapper);

    /**
     * 起页（头一回）——原签名维持不动。
     * 挂接代号、随行签认码由系统发；应挂/已挂校验、欠挂折数与进展同在此处定。
     * 校验不过返回 0，具体毛病取 {@link #openLinkPage(TSaltLinkPage)} 抛出的回执。
     */
    int insertTSaltLinkPage(TSaltLinkPage record);

    /** 修改 */
    int updateTSaltLinkPage(TSaltLinkPage record);

    /**
     * 起页的同义来路：另起一个同义方法承接，校验、重算与 {@link #insertTSaltLinkPage}
     * 走同一套说法，屏上不会长出第二种结果。校验不过抛 IllegalArgumentException，
     * 消息写明毛病出在哪一栏。
     *
     * @return 落库后的页（挂接代号、签认码、欠挂数均已写回）
     */
    TSaltLinkPage openLinkPage(TSaltLinkPage record);

    /**
     * 换挂靠层 / 已挂数变动后重挂保存：与起页同一套校验和欠挂重算，
     * 挂接代号不换手；欠挂一栏由服务层写回，交来的值一律不认。
     *
     * @return 落库后的页
     */
    TSaltLinkPage resaveLinkPage(TSaltLinkPage record);

    /**
     * 名录页开一屏：按操作人归属的属地那一路圈定可见页（县不见邻县、市不夹别市），
     * 三栏（在册/已挂讫/压页）与本屏逐页出自同一次算。
     */
    SaltBoardResult openBoard(SaltBoardQuery query, TSysUserView operator);

    /**
     * 导出名册：与 {@link #openBoard} 同一属地口径、同一套寻页条件，只是不分屏。
     * 列表筛出哪些，导出就装哪些——两处数目永远对得上。
     */
    List<TSaltLinkPage> listBoardRows(SaltBoardQuery query, TSysUserView operator);

    /** 企业总名录：一家一行（代号、全称、类别、属地到县），类别按三类固定次序排 */
    List<TSaltEnt> listEntRegister(String siteType);

    /**
     * 企业总名录停用/恢复（0在册 1已摘牌）。摘牌后不开新页、旧页照旧在册——
     * 状态归档案管，起页那一头只认这里定下的值。
     */
    TSaltEnt changeEntStatus(Long id, Integer status);

    /**
     * 挂接代号对不对得上档案：在册（未沉底）且有这么一页才算数。
     * 备案单登一笔、外模块录代号都先过这一关，人手编一个代号进不来。
     */
    boolean billNoInRegister(String billNo);

    /**
     * 跨模块联动：跨省经营备案单缴回封卷时，把对应挂接页的已挂品种数挪一格。
     * delta 只许 +1（封卷）或 -1（挪回）；应挂品种数一律不碰——应挂归档案，
     * 领一次就把应挂抬一格的账不在这里生。夹在 0..应挂 之内，欠挂随动，进展随齐没齐翻。
     *
     * @return 落库后的挂接页；代号找不着抛 IllegalArgumentException
     */
    TSaltLinkPage applyLinkDone(String billNo, int delta);

    /** 推进一态（待挂→已挂讫→压页，逐态推进，不得跳态） */
    int advanceTSaltLinkPage(Long id);

    /** 退回一态；旧记录沉底 */
    int revertTSaltLinkPage(Long id);

    /** 按主键删除 */
    int deleteTSaltLinkPageById(Long id);

    /** 批量删除 */
    int deleteTSaltLinkPageByIds(String ids);

    /**
     * 操作人属地凭据：部门名（行政区划名）+ 层级序（0省 1市 2县）。
     * 接口层从登录人与部门树取出后交给服务层，服务层不认 HTTP 会话。
     */
    class TSysUserView {
        private final String deptName;
        private final int level;

        public TSysUserView(String deptName, int level) {
            this.deptName = deptName;
            this.level = level;
        }

        public String getDeptName() {
            return deptName;
        }

        public int getLevel() {
            return level;
        }
    }
}
