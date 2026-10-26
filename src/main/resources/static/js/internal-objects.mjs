// Display classification only. This never changes DB privileges or object access.
export function internalObject(name){
  const value=String(name??'').toUpperCase();
  return /^(DR\$|VECTOR\$|SYS_IOT_)/.test(value)||/#(?:RDF|SEM)/.test(value)||value.includes('$VECTAB');
}
