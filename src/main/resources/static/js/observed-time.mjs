/** Convert only instants with an explicit zone. Unzoned DB values remain DB values. */
export function observedTime(value,locale=undefined){
  if(!value)return '—';const text=String(value);
  if(!/(?:Z|[+-]\d{2}:?\d{2})$/.test(text))return `${text} (DB)`;
  const date=new Date(text);if(Number.isNaN(date.getTime()))return text;
  return `${date.toLocaleString(locale,{timeZoneName:'short'})} · UTC ${date.toISOString()}`;
}
