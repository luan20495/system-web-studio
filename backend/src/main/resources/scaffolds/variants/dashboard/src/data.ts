export type Row = { label: string; revenue: number; orders: number };
/** url: optional JSON source (an array of rows) on the same sites host, e.g. the API of a server app: "../my-tool/api/stats" */
export const SAMPLE: { url: string | null; rows: Row[] } = {
  url: null,
  rows: [
    { label: "T1", revenue: 120, orders: 34 }, { label: "T2", revenue: 150, orders: 41 }, { label: "T3", revenue: 90, orders: 28 },
    { label: "T4", revenue: 180, orders: 52 }, { label: "T5", revenue: 210, orders: 60 }, { label: "T6", revenue: 170, orders: 47 }
  ]
};
