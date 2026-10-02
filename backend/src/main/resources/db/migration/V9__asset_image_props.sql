-- Optional images on Hero and ProductGrid items, referenced as "asset://<asset uuid>" (never a raw URL).
-- Additive change to the 1.0.0 props schema: existing pages stay valid; the renderer resolves the reference to a short-lived
-- signed URL for an asset of the same project, and commits/publishes verify the asset belongs to that project.
UPDATE component_versions
   SET props_schema = jsonb_set(props_schema, '{properties,image}', '{"type":"string","format":"asset","maxLength":80}')
 WHERE component_id = 'Hero' AND version = '1.0.0';
UPDATE component_versions
   SET props_schema = jsonb_set(props_schema, '{properties,items,itemProperties,image}', '{"type":"string","format":"asset","maxLength":80}')
 WHERE component_id = 'ProductGrid' AND version = '1.0.0';
