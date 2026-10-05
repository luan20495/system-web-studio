INSERT INTO components (id, name, category, description, latest_version) VALUES
 ('LandingTemplate', 'Landing template', 'TEMPLATE', 'One-page marketing site layout', '1.0.0'),
 ('Navbar', 'Navigation bar', 'UI', 'Top navigation with brand and anchor links', '1.0.0'),
 ('Hero', 'Hero', 'BLOCK', 'Headline, supporting text and call to action', '1.0.0'),
 ('ProductGrid', 'Product grid', 'BLOCK', 'Grid of product cards', '1.0.0'),
 ('ProductCard', 'Product card', 'BUSINESS', 'Single product summary', '1.0.0'),
 ('TechnologySection', 'Technology section', 'BLOCK', 'Heading and explanatory text', '1.0.0'),
 ('ComparisonBlock', 'Comparison block', 'BLOCK', 'Side by side product comparison table', '1.0.0'),
 ('Testimonials', 'Testimonials', 'BLOCK', 'Customer quotes', '1.0.0'),
 ('ContactForm', 'Contact form', 'BUSINESS', 'Lead capture form', '1.0.0'),
 ('Footer', 'Footer', 'UI', 'Site footer', '1.0.0');

INSERT INTO component_versions (component_id, version, props_schema, actions, permissions) VALUES
 ('LandingTemplate', '1.0.0', '{"required":[],"properties":{"theme":{"type":"string","enum":["light","dark"]}}}', '[]', '["PROJECT_EDIT"]'),
 ('Navbar', '1.0.0', '{"required":["brand"],"properties":{"brand":{"type":"string","maxLength":80},"links":{"type":"array","maxItems":10}}}', '["navigate"]', '["PROJECT_EDIT"]'),
 ('Hero', '1.0.0', '{"required":["title"],"properties":{"eyebrow":{"type":"string","maxLength":120},"title":{"type":"string","maxLength":200},"description":{"type":"string","maxLength":600},"ctaLabel":{"type":"string","maxLength":60}}}', '["navigate"]', '["PROJECT_EDIT"]'),
 ('ProductGrid', '1.0.0', '{"required":["heading","items"],"properties":{"heading":{"type":"string","maxLength":160},"items":{"type":"array","maxItems":24,"itemRequired":["id","name"],"itemProperties":{"id":{"type":"string","maxLength":64},"name":{"type":"string","maxLength":120},"description":{"type":"string","maxLength":400}}}}}', '["edit-item"]', '["PROJECT_EDIT"]'),
 ('ProductCard', '1.0.0', '{"required":["name"],"properties":{"name":{"type":"string","maxLength":120},"description":{"type":"string","maxLength":400}}}', '[]', '["PROJECT_EDIT"]'),
 ('TechnologySection', '1.0.0', '{"required":["heading"],"properties":{"heading":{"type":"string","maxLength":160},"body":{"type":"string","maxLength":1200}}}', '[]', '["PROJECT_EDIT"]'),
 ('ComparisonBlock', '1.0.0', '{"required":["heading","rows"],"properties":{"heading":{"type":"string","maxLength":160},"columns":{"type":"array","maxItems":6},"rows":{"type":"array","maxItems":24,"itemRequired":["id","label"],"itemProperties":{"id":{"type":"string","maxLength":64},"label":{"type":"string","maxLength":120},"values":{"type":"array","maxItems":6}}}}}', '[]', '["PROJECT_EDIT"]'),
 ('Testimonials', '1.0.0', '{"required":["heading","items"],"properties":{"heading":{"type":"string","maxLength":160},"visible":{"type":"boolean"},"items":{"type":"array","maxItems":24,"itemRequired":["id","quote","author"],"itemProperties":{"id":{"type":"string","maxLength":64},"quote":{"type":"string","maxLength":500},"author":{"type":"string","maxLength":120},"location":{"type":"string","maxLength":120},"rating":{"type":"number"}}}}}', '[]', '["PROJECT_EDIT"]'),
 ('ContactForm', '1.0.0', '{"required":["heading"],"properties":{"heading":{"type":"string","maxLength":160},"submitLabel":{"type":"string","maxLength":60}}}', '["submit-lead"]', '["PROJECT_EDIT"]'),
 ('Footer', '1.0.0', '{"required":["text"],"properties":{"text":{"type":"string","maxLength":300}}}', '[]', '["PROJECT_EDIT"]');
