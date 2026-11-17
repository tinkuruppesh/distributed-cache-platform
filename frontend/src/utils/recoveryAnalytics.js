const isMeasurement = (value) => typeof value === 'number' && Number.isFinite(value) && value >= 0;

export function averageCompletedMeasurement(rows, field) {
  const values = rows
    .filter((row) => row.outcome === 'COMPLETED')
    .map((row) => row[field])
    .filter(isMeasurement);
  return values.length
    ? { value: values.reduce((sum, value) => sum + value, 0) / values.length, count: values.length }
    : { value: null, count: 0 };
}

export function overallRequestSuccess(rows) {
  let requests = 0;
  let succeeded = 0;
  for (const row of rows) {
    const total = row.requestsTotal;
    const success = row.requestsSucceeded;
    if (!isMeasurement(total) || !isMeasurement(success) || success > total) continue;
    requests += total;
    succeeded += success;
  }
  return requests > 0 ? { value: (succeeded / requests) * 100, succeeded, requests } : null;
}
