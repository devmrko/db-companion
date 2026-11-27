/** Decide whether a message is a question before handing it to a value prompt. */
export function isConversationQuestion(value){
  return /[?？]|(?:가능|되나|되나요|되냐|될까|뭐야|뭔데|왜|무엇|어떻게|어떤|얼마|어디|알려|설명)|(?:^|\s)(?:what|why|how|where|can|could|is|are)\b|できます|ですか|什么|怎么|可以吗/i.test(value);
}
export function isCloneRequest(value,inProfileTask=false){
  return !/(?:하지\s*마|말고|않|안\s*(?:할|해|하)|취소|don't|do not|without|feedback|피드백)/i.test(value)&&/(?:복제|복사|clone|copy|コピー|复制)/i.test(value)&&
    (inProfileTask||/(?:프로필|프로파일|profile|기존|이전|원본|clone|コピー|复制)/i.test(value));
}
export function fieldQuestion(value){
  if(!isConversationQuestion(value))return null;
  for(const [key,pattern]of [['name',/이름|name/i],['model',/모델|model/i],['provider',/제공자|provider/i],['credential_name',/credential|인증/i],['region',/리전|region/i],['oci_compartment_id',/compartment|컴파트먼트/i]])if(pattern.test(value))return key;
  return 'general';
}
/** Global requests interrupt value prompts; exact choices remain values. */
export function turnDisposition(intent,{hasReply=false,literal=false,creating=false,content=false}={}){
  if(intent==='cancel')return 'dispatch';
  if(literal)return 'reply';
  if(content&&hasReply)return 'reply';
  if(['permissions','navigation','help'].includes(intent))return 'dispatch';
  if(!content&&['clone-profile','create-profile','query-profile','glossary','glossary-list','history','sources'].includes(intent))return 'dispatch';
  if(creating&&intent==='attribute')return 'reply';
  return hasReply?'reply':'dispatch';
}
