-- Apply to TEST first. Owner requested changing the existing shared role,
-- including Fangdao/Sam/Yuge/Yangzong/studentintake, not creating a new role.
-- Keep process-instance:query (approval details) and task query/update.
-- Back up role-menu rows first and evict only affected permission caches afterwards.
START TRANSACTION;
UPDATE system_role_menu rm
JOIN system_role r ON r.id = rm.role_id AND r.deleted = 0
JOIN system_menu m ON m.id = rm.menu_id AND m.deleted = 0
SET rm.deleted = 1
WHERE r.code = 'screenwriting_member' AND rm.deleted = 0
  AND ((m.type = 2 AND m.component IN ('bpm/processInstance/index', 'bpm/processInstance/create/index'))
       OR m.permission = 'bpm:process-instance:create');
COMMIT;
