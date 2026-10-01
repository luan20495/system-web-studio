DELETE FROM workspaces AS workspace
WHERE workspace.id = '00000000-0000-0000-0000-000000000001'
  AND workspace.slug = 'local'
  AND NOT EXISTS (
      SELECT 1
      FROM projects AS project
      WHERE project.workspace_id = workspace.id
  );