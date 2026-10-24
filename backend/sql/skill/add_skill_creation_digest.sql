ALTER TABLE lc_skill ADD COLUMN creation_digest varchar(64);
COMMENT ON COLUMN lc_skill.creation_digest IS '首次创建有效载荷 SHA256，含基本信息、标签和整包内容；历史记录为空';
