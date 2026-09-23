import {t} from './i18n.mjs';
const keywords=new Set('ACCESSIBLE AGGREGATE ALL ALTER AND AS ASC AUTHID BEGIN BETWEEN BODY BULK BY CASE CAST CLOSE COLLECT COMMIT CONSTANT CONTINUE CREATE CURRENT_USER CURSOR DECLARE DEFAULT DEFINER DELETE DESC DETERMINISTIC DISTINCT ELSE ELSIF END EXCEPTION EXECUTE EXISTS EXIT FETCH FOR FORALL FROM FUNCTION GROUP HAVING IF IMMEDIATE IN INSERT INTERSECT INTO IS JOIN LEFT LIKE LIMIT LOOP MERGE MINUS NOT NULL OF ON OPEN OR ORDER OTHERS OUT OVER PACKAGE PARALLEL_ENABLE PIPELINED PRAGMA PROCEDURE RAISE RECORD REPLACE RESULT_CACHE RETURN RETURNS REVERSE RIGHT ROLLBACK ROW ROWTYPE SELECT SET SQL TABLE THEN TYPE UNION UNIQUE UPDATE USING VALUES WHEN WHERE WHILE WITH'.split(' '));
const types=new Set('BOOLEAN CHAR CLOB DATE INTEGER NCHAR NCLOB NUMBER NVARCHAR2 PLS_INTEGER RAW SYS_REFCURSOR TIMESTAMP VARCHAR VARCHAR2 VECTOR'.split(' '));
// A display lexer only: never execute SQL, parse HTML, or rewrite the source.
export function sourceTokens(text) {
  const result=[];let i=0;
  const push=(end,kind='')=>{result.push({text:text.slice(i,end),kind,start:i});i=end;};
  while(i<text.length){
    const rest=text.slice(i);let match;
    if(rest.startsWith('--')){let end=text.indexOf('\n',i);push(end<0?text.length:end,'comment');}
    else if(rest.startsWith('/*')){let end=text.indexOf('*/',i+2);push(end<0?text.length:end+2,'comment');}
    else if((match=/^(?:n)?q'([^\s])/i.exec(rest))){
      const open=match[1],close=({'[':']','{':'}','(':')','<':'>'})[open]??open;
      const end=text.indexOf(close+"'",i+match[0].length);push(end<0?text.length:end+2,'string');
    }else if((match=/^(?:n)?'/i.exec(rest))||rest[0]==='"'){
      const quote=rest[0]==='"'?'"':"'";let end=i+(quote==='"'?1:match[0].length);
      while(end<text.length){if(text[end++]===quote){if(text[end]===quote){end++;continue;}break;}}
      push(end,quote==='"'?'identifier':'string');
    }else if((match=/^[\p{L}_$#][\p{L}\p{N}_$#]*/u.exec(rest))){
      const word=match[0].toUpperCase();push(i+match[0].length,keywords.has(word)?'keyword':types.has(word)?'type':'identifier');
    }else if((match=/^\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/.exec(rest)))push(i+match[0].length,'number');
    else if((match=/^\s+/.exec(rest)))push(i+match[0].length);
    else push(i+1);
  }
  return result;
}
const identifier=token=>token?.kind==='identifier';
const identifierName=token=>token.text.startsWith('"')?token.text.slice(1,-1).replaceAll('""','"'):token.text.toUpperCase();
const referenceKey=parts=>JSON.stringify(parts);
export function packageFunctionIndex(owner,entries){
  const result=new Map();
  for(const entry of entries){
    if(!entry.member)continue;
    const key=referenceKey([owner,entry.object,entry.member]);
    // Do not guess when distinct catalog entries claim the same name.
    if(result.has(key)&&result.get(key)!==entry.reference)result.set(key,null);
    else if(!result.has(key))result.set(key,entry.reference);
  }
  return result;
}
export function referenceTokens(tokens,owner,index){
  if(!index?.size)return tokens;
  const significant=tokens.filter(token=>token.kind==='string'||token.kind!=='comment'&&!/^\s+$/.test(token.text));
  const shadowed=new Set(),links=new Map();
  for(let i=0;i<significant.length;i++){
    const current=significant[i],before=significant[i-1],after=significant[i+1];
    if(!identifier(current)||before?.text==='.')continue;
    // Conservative declaration check: parameters, variables, loop variables and local routines.
    // Not a compiler symbol table: uncertainty suppresses a link rather than inventing a target.
    if(['identifier','type'].includes(after?.kind)||['IN','OUT','CONSTANT'].includes(after?.text.toUpperCase())
        ||['FUNCTION','PROCEDURE','TYPE','CURSOR'].includes(before?.text.toUpperCase()))shadowed.add(identifierName(current));
  }
  for(let i=0;i<significant.length;i++){
    const first=significant[i],before=significant[i-1];
    if(!identifier(first)||['.','@',':'].includes(before?.text))continue;
    const parts=[first];let last=i;
    while(significant[last+1]?.text==='.'&&identifier(significant[last+2])){parts.push(significant[last+2]);last+=2;}
    if((parts.length===2||parts.length===3)&&significant[last+1]?.text==='('
        &&!shadowed.has(identifierName(first))&&!['FUNCTION','PROCEDURE'].includes(before?.text.toUpperCase())){
      const names=parts.map(identifierName),key=referenceKey(parts.length===2?[owner,...names]:names);
      const reference=index.get(key);if(reference)links.set(parts.at(-1).start,reference);
    }
    i=last;
  }
  return tokens.map(token=>links.has(token.start)?{...token,reference:links.get(token.start)}:token);
}
export function sourceLines(text,context=null){
  let tokens=text.length<=250000?sourceTokens(text):[{text,kind:'',start:0}];
  if(context&&text.length<=250000)tokens=referenceTokens(tokens,context.owner,context.index);
  const lines=[[]];
  for(const token of tokens){
    token.text.split('\n').forEach((part,index)=>{if(index)lines.push([]);if(part)lines.at(-1).push({text:part,kind:token.kind,...(token.reference?{reference:token.reference}:{})});});
  }
  return lines;
}
export function matchingLines(text,query){
  if(!query)return [];
  const key=query.toLocaleUpperCase();return text.split('\n').flatMap((line,index)=>line.toLocaleUpperCase().includes(key)?[index+1]:[]);
}
export function declarationLine(text,member){
  if(!member||text.length>250000)return 1;
  const tokens=sourceTokens(text).filter(token=>token.kind&&token.kind!=='comment');
  for(let i=0;i+1<tokens.length;i++){
    if(!['FUNCTION','PROCEDURE'].includes(tokens[i].text.toUpperCase())||tokens[i].kind!=='keyword')continue;
    const next=tokens[i+1],name=next.text.startsWith('"')?next.text.slice(1,-1).replaceAll('""','"'):next.text.toUpperCase();
    if(next.kind==='identifier'&&name===member)return text.slice(0,tokens[i].start).split('\n').length;
  }
  return 1;
}
const el=(tag,text,cls)=>{const e=document.createElement(tag);if(text!==undefined)e.textContent=text;if(cls)e.className=cls;return e;};
export function mountSourceViewer(host,sections,member,references=null){
  const controls=el('div',undefined,'app-source-toolbar'),select=el('select',undefined,'form-select app-select');
  select.setAttribute('aria-label',t('functions.source','소스'));
  sections.forEach((section,index)=>{const option=el('option',section.type);option.value=String(index);select.append(option);});
  const bodyIndex=sections.findIndex(section=>section.type==='PACKAGE BODY');select.value=String(Math.max(bodyIndex,0));
  const search=el('input',undefined,'form-control');search.type='search';search.placeholder=t('functions.find','소스 검색');search.setAttribute('aria-label',search.placeholder);
  const button=text=>{const e=el('button',text,'btn app-btn app-btn-quiet');e.type='button';return e;};
  const previous=button(t('ui.da7e61c67cc5','이전')),next=button(t('ui.aef613c6612d','다음')),
    wrap=button(t('functions.wrap','줄바꿈')),copy=button(t('functions.copy','복사'));
  wrap.setAttribute('aria-pressed','false');
  const result=el('span','','app-source-result');result.setAttribute('role','status');
  const viewport=el('div',undefined,'app-source-viewport');viewport.tabIndex=0;viewport.setAttribute('role','region');viewport.setAttribute('aria-label',t('functions.readOnlySource','읽기 전용 소스'));
  const note=el('p','','app-filter-message');note.setAttribute('role','status');
  controls.append(select,search,previous,next,result,wrap,copy);host.append(controls,note,viewport);
  let text='',matches=[],index=-1,rows=[];
  function move(line){
    rows.forEach(row=>row.classList.remove('is-current'));
    const row=rows[line-1];if(row){row.classList.add('is-current');viewport.scrollTop=Math.max(0,row.offsetTop-60);}
  }
  function find(direction=0){
    if(!direction){matches=matchingLines(text,search.value);index=matches.length?0:-1;}
    else if(matches.length)index=(index+direction+matches.length)%matches.length;
    previous.disabled=next.disabled=matches.length===0;
    result.textContent=search.value?t('functions.matches','일치 줄 {0} / {1}',index+1,matches.length):'';
    if(index>=0)move(matches[index]);else rows.forEach(row=>row.classList.remove('is-current'));
  }
  function render(){
    text=sections[Number(select.value)].text;viewport.replaceChildren();rows=[];search.value='';find();
    const wrapped=/^\s*(?:(?:create\s+(?:or\s+replace\s+)?)?(?:function|procedure|package)\b)[\s\S]*?\bwrapped\s*\r?\n/i.test(text.slice(0,1000));
    note.textContent=wrapped?t('ui.c22eaf5da82e','Wrapped 소스는 저장된 형태로 표시합니다.'):text.length>250000?t('functions.plain','큰 소스는 구문 강조 없이 표시합니다.') :'';note.hidden=!note.textContent;
    const fragment=document.createDocumentFragment();
    sourceLines(text,wrapped?null:references).forEach((parts,i)=>{
      const row=el('div',undefined,'app-source-line'),number=el('span',String(i+1),'app-source-number'),code=el('code');
      number.setAttribute('aria-hidden','true');parts.forEach(part=>{
        if(part.reference&&references?.href){
          const link=el('a',part.text,'app-source-link');link.href=references.href(part.reference);
          link.title=`${t('functions.title','함수')} · ${part.reference}`;code.append(link);
        }else code.append(el('span',part.text,part.kind?'app-syntax-'+part.kind:undefined));
      });
      if(!parts.length)code.append(document.createTextNode('\u200b'));
      row.append(number,code);rows.push(row);fragment.append(row);
    });viewport.append(fragment);move(declarationLine(text,member));
  }
  search.addEventListener('input',()=>find());search.addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();find(e.shiftKey?-1:1);}});
  previous.addEventListener('click',()=>find(-1));next.addEventListener('click',()=>find(1));select.addEventListener('change',render);
  wrap.addEventListener('click',()=>{const on=viewport.classList.toggle('is-wrapped');wrap.setAttribute('aria-pressed',String(on));});
  copy.addEventListener('click',async()=>{try{await navigator.clipboard.writeText(text);result.textContent=t('functions.copied','복사했습니다.');}catch(_){result.textContent=t('functions.copyError','복사하지 못했습니다. 소스를 선택해 복사해 주세요.');}});
  render();
}
