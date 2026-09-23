import {mountHistory} from './team-history.mjs';
if(typeof document!=='undefined')document.querySelectorAll('[data-object-history]').forEach(dialog=>mountHistory(dialog,{objectMode:true}));
