export type Visibility = "private" | "public";
export type DeviceMode = "desktop" | "tablet" | "mobile";

export type Project = {
  id: string;
  name: string;
  owner: string;
  branch: string;
  visibility: Visibility;
  framework: string;
  authMode: "sso" | "password" | "public";
  domain: string;
  customDomain?: string;
  deploymentMode: "auto" | "static" | "dynamic";
  deploymentTarget: "self-host" | "aws" | "azure" | "gcp";
};

export type Product = {
  id: string;
  name: string;
  description: string;
};

export type Testimonial = {
  id: string;
  author: string;
  location: string;
  quote: string;
  rating: number;
};

export type PageContent = {
  heroEyebrow: string;
  heroTitle: string;
  heroDescription: string;
  products: Product[];
  testimonials: Testimonial[];
  showTestimonials: boolean;
  showComparison: boolean;
};

export type ChatMessage = {
  id: string;
  role: "user" | "assistant";
  content: string;
  meta?: string[];
};

export type Version = {
  id: string;
  label: string;
  createdAt: string;
  summary: string;
  sourceRevision?: string | null;
};

export type StudioSnapshot = {
  project: Project;
  content: PageContent;
  messages: ChatMessage[];
  versions: Version[];
};

export type PromptResult = {
  content: PageContent;
  message: ChatMessage;
  outcome: "updated" | "no-change" | "unsupported";
  version?: Version;
};

export type PublishResult =
  | { status: "demo"; visibility: Visibility }
  | { status: "success"; visibility: Visibility; url: string };
