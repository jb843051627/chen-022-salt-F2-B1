-- salt 食盐专营管理与食盐质量安全监管 -- schema (chen-022)
-- 列名与基线实体契约（@TableName/@TableField）逐列对齐，改列必须同步实体。
-- 库：chen_022

CREATE TABLE IF NOT EXISTS t_salt_ent (
  id bigint NOT NULL COMMENT '主键',
  site_no varchar(64) DEFAULT NULL COMMENT '食盐定点企业代号',
  site_name varchar(128) DEFAULT NULL COMMENT '企业全称',
  site_type varchar(32) DEFAULT NULL COMMENT '企业类别',
  road_name varchar(128) DEFAULT NULL COMMENT '属地那一路(省—市—县)',
  status int DEFAULT NULL COMMENT '名录情形 0在册 1已摘牌',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='食盐定点企业总名录';

CREATE TABLE IF NOT EXISTS t_salt_iod_line (
  id bigint NOT NULL COMMENT '主键',
  rule_code varchar(64) DEFAULT NULL COMMENT '碘含量分档判定线代号',
  rule_name varchar(128) DEFAULT NULL COMMENT '线名',
  th1_max decimal(12,3) DEFAULT NULL COMMENT '起算数值(毫克每千克)',
  th2_max decimal(12,3) DEFAULT NULL COMMENT '够线数值(毫克每千克)',
  th3_max decimal(12,3) DEFAULT NULL COMMENT '封顶数值(毫克每千克)',
  eff_start datetime DEFAULT NULL COMMENT '立线之日',
  eff_end datetime DEFAULT NULL COMMENT '让位之日(不含)',
  priority int DEFAULT NULL COMMENT '让位顺位(数值大的先说话)',
  status int DEFAULT NULL COMMENT '线的情形 0在场 1已停用',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='碘含量分档判定线';

CREATE TABLE IF NOT EXISTS t_salt_link_page (
  id bigint NOT NULL COMMENT '主键',
  bill_no varchar(64) DEFAULT NULL COMMENT '企业品种挂接代号（系统发，人手不收）',
  sign_code varchar(64) DEFAULT NULL COMMENT '随行签认码（系统发，人手不收）',
  node_no int DEFAULT NULL COMMENT '适用区域层级序',
  site_id int DEFAULT NULL COMMENT '所属定点企业',
  site_no varchar(64) DEFAULT NULL COMMENT '所属企业代号',
  scope_road varchar(128) DEFAULT NULL COMMENT '页归属的属地那一路(省—市—县，压在企业属地那一层)',
  should_count decimal(12,2) DEFAULT NULL COMMENT '应挂品种数（档案口径两位，写入四舍五入）',
  done_count decimal(12,2) DEFAULT NULL COMMENT '已挂品种数（档案口径两位，写入四舍五入）',
  lack_count decimal(12,2) DEFAULT NULL COMMENT '欠挂品种数（服务层算后写回，只许看不许改；两位小数）',
  content varchar(255) DEFAULT NULL COMMENT '随页交来的标签要件',
  status int DEFAULT NULL COMMENT '挂接进展 0待挂 1已挂讫 2压页',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_bill_no (bill_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企业品种挂接簿页';

CREATE TABLE IF NOT EXISTS t_salt_record_flow (
  id bigint NOT NULL COMMENT '主键',
  biz_no varchar(64) DEFAULT NULL COMMENT '跨省经营备案单代号',
  stage int DEFAULT NULL COMMENT '当前步次 0..3（递单受理/材料核验/生效确认/缴回封卷）',
  status int DEFAULT NULL COMMENT '备案单走到的那一步 0未起 1在办 2已封卷',
  content varchar(255) DEFAULT NULL COMMENT '一段一记',
  last_action varchar(64) DEFAULT NULL COMMENT '最近一次挪步动作',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='跨省经营备案单';

CREATE TABLE IF NOT EXISTS t_salt_renew_bill (
  id bigint NOT NULL COMMENT '主键',
  bill_no varchar(64) DEFAULT NULL COMMENT '延续换证核签单',
  node_no int DEFAULT NULL COMMENT '当前所在档口 0..2（县初核/市复审/省核准）',
  sign_mode int DEFAULT NULL COMMENT '同档算齐办法 0一名即可 1两名须同判',
  need_count int DEFAULT NULL COMMENT '本档应落人数',
  sign_count int DEFAULT NULL COMMENT '本档已落印数',
  status int DEFAULT NULL COMMENT '核签情形 0在核 1已办结 2已挪回',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='延续换证核签单';

CREATE TABLE IF NOT EXISTS t_salt_spot_row (
  id bigint NOT NULL COMMENT '主键',
  batch_no varchar(64) DEFAULT NULL COMMENT '监督抽检结果报送册码',
  row_no int DEFAULT NULL COMMENT '原报行次',
  item_code varchar(64) DEFAULT NULL COMMENT '被对上的定点企业代号',
  qty decimal(12,2) DEFAULT NULL COMMENT '本行不合格件数',
  hours_num decimal(12,2) DEFAULT NULL COMMENT '本行送检件数',
  status int DEFAULT NULL COMMENT '行落地情形 0未勾 1已收下 2已退回',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='抽检结果报送核收行';

CREATE TABLE IF NOT EXISTS t_salt_year_task (
  id bigint NOT NULL COMMENT '主键',
  item_no varchar(64) DEFAULT NULL COMMENT '年检催办条目代号',
  due_at datetime DEFAULT NULL COMMENT '应开口那一日的止点时刻',
  amount decimal(12,2) DEFAULT NULL COMMENT '最可提前几日开口',
  content varchar(255) DEFAULT NULL COMMENT '事由与所对企业的记要',
  status int DEFAULT NULL COMMENT '条目情形 0待派 1已派出 2分不出',
  del_flag int DEFAULT '0' COMMENT '删除标记 0正常 1删除',
  create_by varchar(64) DEFAULT NULL COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT NULL COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='年检到期催办单';

-- 已部署旧库的口径迁移（幂等，可重复执行）：应挂/已挂/欠挂由 int 改为两位小数，
-- 与档案 decimal(12,2) 对齐；存量整数按 12.00 落位。
ALTER TABLE t_salt_link_page
  MODIFY COLUMN should_count decimal(12,2) DEFAULT NULL COMMENT '应挂品种数（档案口径两位，写入四舍五入）',
  MODIFY COLUMN done_count decimal(12,2) DEFAULT NULL COMMENT '已挂品种数（档案口径两位，写入四舍五入）',
  MODIFY COLUMN lack_count decimal(12,2) DEFAULT NULL COMMENT '欠挂品种数（服务层算后写回，只许看不许改；两位小数）';

-- 初始档案数据（状态约定：id=1 启用 / id=2 停用）
-- t_salt_ent 预置两条种子名录：id=0 在册、id=1 已摘牌；企业代号与类别按各表既有排法给值。
INSERT IGNORE INTO t_salt_ent (id, site_no, site_name, site_type, road_name, status, del_flag, create_by, create_time)
VALUES (0, 'JY00', '盐泽制盐一厂（制盐企业，在册）', '制盐企业', '盐岭省—盐泽市—临卤县', 0, 0, 'seed', NOW()),
       (1, 'JY01', '青卤批发部（转行后摘牌）', '省内批发企业', '盐岭省—盐泽市—青卤县', 1, 0, 'seed', NOW());

