//! GitHub 云端拉面杯卡组基准。
//! 卡组始终为 5 张普通支援卡 + 1 张固定友人卡（共 6 张）。

use std::{collections::{BTreeMap, HashSet}, env, fs, time::Instant};
use rand::SeedableRng;
use rayon::prelude::*;
use serde::Deserialize;
use uma_jni::ramen_strategy::RamenStrategy;
use umasim::{game::{Game, InheritInfo, ramen::RamenGame}, gamedata::init_global};

const TEST_UMA:u32=102601; const FRIEND:u32=303054;
const BASELINE:[u32;6]=[302424,302894,303044,302924,303024,FRIEND];
/// 基准第 4 张速度卡是力/根逐卡测试槽；替换后仍严格只有 6 张卡。
const TEST_SLOT:usize=3; const DEFAULT_MIN_CARD_ID:u32=30200;
const INHERIT:InheritInfo=InheritInfo{blue_count:[12,0,0,0,6],extra_count:[10,0,0,20,20,40]};
#[derive(Clone,Debug)]struct Card{id:u32,name:String,card_type:usize,proxy:f64}
#[derive(Deserialize,Default)]#[serde(default,rename_all="camelCase")]struct RawCard{card_id:u32,card_name:String,rarity:i32,card_type:usize,card_value:Vec<RawValue>}
#[derive(Deserialize,Default)]#[serde(default,rename_all="camelCase")]struct RawValue{bonus:Vec<i32>,initial_bonus:Vec<i32>,hint_bonus:Vec<i32>,you_qing:f64,gan_jing:f64,xun_lian:f64,initial_ji_ban:f64,hint_prob_increase:f64,de_yi_lv:f64,sai_hou:f64,wiz_vital_bonus:f64,fail_rate_drop:f64,vital_cost_drop:f64}
#[derive(Clone)]struct Deck{name:String,cards:[u32;6]}
struct ResultRow{name:String,cards:[u32;6],ok:usize,failed:usize,mean:f64,median:i32,p90:i32,max:i32,pt_mean:f64,rmj_all:f64,elapsed:f64}
fn sum(v:&[i32])->f64{v.iter().map(|&x|x as f64).sum()}
fn load_cards(path:&str,min_id:u32)->anyhow::Result<Vec<Card>>{let raw:BTreeMap<String,RawCard>=serde_json::from_str(&fs::read_to_string(path)?)?;Ok(raw.into_values().filter_map(|c|{if c.rarity!=3||c.card_type>=5||c.card_value.len()<5||c.card_id<min_id{return None}let v=&c.card_value[4];let proxy=v.xun_lian*3.0+v.you_qing*2.0+v.de_yi_lv*.35+v.gan_jing*.25+v.initial_ji_ban*.45+v.sai_hou*1.5+v.wiz_vital_bonus*8.0+v.fail_rate_drop+v.vital_cost_drop+sum(&v.bonus)*16.0+sum(&v.initial_bonus)*.4+sum(&v.hint_bonus)*.15+v.hint_prob_increase*.1;Some(Card{id:c.card_id*10+4,name:c.card_name,card_type:c.card_type,proxy})}).collect())}
fn top_by_type(cards:&[Card],t:usize,n:usize)->Vec<Card>{let mut v:Vec<_>=cards.iter().filter(|c|c.card_type==t).cloned().collect();v.sort_by(|a,b|b.proxy.total_cmp(&a.proxy));v.truncate(n);v}
fn choose(pool:&[Card],count:usize,used:&mut HashSet<u32>)->Vec<u32>{pool.iter().filter_map(|c|if used.insert(c.id){Some(c.id)}else{None}).take(count).collect()}
fn archetype(name:&str,counts:[usize;5],pools:&[Vec<Card>;5])->Option<Deck>{let mut used=HashSet::new();let mut ids=vec![];for t in 0..5{ids.extend(choose(&pools[t],counts[t],&mut used));}if ids.len()!=5{return None}ids.push(FRIEND);let cards:[u32;6]=ids.try_into().ok()?;debug_assert_eq!(cards.len(),6);Some(Deck{name:name.into(),cards})}
fn build_decks(cards:&[Card])->Vec<Deck>{
 let pools:[Vec<Card>;5]=std::array::from_fn(|t|top_by_type(cards,t,10));let mut decks=vec![Deck{name:"上游推荐基准：3速1耐1智+友".into(),cards:BASELINE}];
 for(name,counts)in[("2速1耐1力1智+友",[2,1,1,0,1]),("2速1耐1根1智+友",[2,1,0,1,1]),("1速1耐2力1智+友",[1,1,2,0,1]),("1速1耐2根1智+友",[1,1,0,2,1]),("2速1力1根1智+友",[2,0,1,1,1])]{if let Some(d)=archetype(name,counts,&pools){decks.push(d)}}
 // 专项逐卡：用基准第4张速度卡作为唯一测试槽。每组仍是 5 张普通卡 + 固定友人。
 let mut power:Vec<_>=cards.iter().filter(|c|c.card_type==2).cloned().collect();power.sort_by(|a,b|b.proxy.total_cmp(&a.proxy));
 let mut guts:Vec<_>=cards.iter().filter(|c|c.card_type==3).cloned().collect();guts.sort_by(|a,b|b.proxy.total_cmp(&a.proxy));
 for c in power{let mut d=BASELINE;d[TEST_SLOT]=c.id;decks.push(Deck{name:format!("力卡逐测：{}（替换速度槽）",c.name),cards:d})}
 for c in guts{let mut d=BASELINE;d[TEST_SLOT]=c.id;decks.push(Deck{name:format!("根卡逐测：{}（替换速度槽）",c.name),cards:d})}
 let mut seen=HashSet::new();decks.retain(|d|d.cards.len()==6&&d.cards[5]==FRIEND&&seen.insert(d.cards));decks
}
fn simulate(deck:&Deck,n:usize,strategy:&RamenStrategy)->ResultRow{assert_eq!(deck.cards.len(),6);assert_eq!(deck.cards[5],FRIEND);let start=Instant::now();let results:Vec<(i32,i32,bool)>=(0..n).into_par_iter().filter_map(|_|{let mut g=RamenGame::newgame(TEST_UMA,&deck.cards,INHERIT).ok()?;let mut rng=rand::rngs::StdRng::from_os_rng();g.run_full_game(strategy,&mut rng).ok()?;Some((g.uma().calc_score(),g.uma().total_pt(),g.ramen.rmj_results.iter().take(3).all(|&x|x)))}).collect();let ok=results.len();let mut s:Vec<_>=results.iter().map(|x|x.0).collect();s.sort_unstable();let mean=if ok==0{0.0}else{s.iter().map(|&x|x as f64).sum::<f64>()/ok as f64};let pt=if ok==0{0.0}else{results.iter().map(|x|x.1 as f64).sum::<f64>()/ok as f64};let rmj=if ok==0{0.0}else{results.iter().filter(|x|x.2).count()as f64*100.0/ok as f64};ResultRow{name:deck.name.clone(),cards:deck.cards,ok,failed:n-ok,mean,median:s.get(ok/2).copied().unwrap_or(0),p90:s.get((ok*9/10).min(ok.saturating_sub(1))).copied().unwrap_or(0),max:s.last().copied().unwrap_or(0),pt_mean:pt,rmj_all:rmj,elapsed:start.elapsed().as_secs_f64()}}
fn main()->anyhow::Result<()>{let args:Vec<_>=env::args().collect();let n=args.get(1).and_then(|x|x.parse().ok()).unwrap_or(300);let min_id=env::var("MIN_CARD_ID").ok().and_then(|x|x.parse().ok()).unwrap_or(DEFAULT_MIN_CARD_ID);init_global()?;let cards=load_cards(&env::var("CARD_DB").unwrap_or_else(|_|"gamedata/cardDB.json".into()),min_id)?;let names:BTreeMap<u32,String>=cards.iter().map(|c|(c.id,c.name.clone())).collect();let decks=build_decks(&cards);let strategy=env::var("RAMEN_STRATEGY").ok().and_then(|s|serde_json::from_str(&s).ok()).unwrap_or_default();println!("# 力卡/根卡专项模拟\n\n- 每套严格 6 张：5 张普通卡 + 固定友人 {}\n- 力卡或根卡替换基准的一个速度槽，不是额外添加\n- 仅近期满破 SSR（card_id >= {}），每套 {} 局\n",FRIEND,min_id,n);let mut rows:Vec<_>=decks.iter().map(|d|{eprintln!("模拟 {} {:?}",d.name,d.cards);simulate(d,n,&strategy)}).collect();rows.sort_by(|a,b|b.mean.total_cmp(&a.mean));println!("|排名|卡组|均分|中位|P90|最高|均PT|RMJ全通|成功/失败|\n|---:|---|---:|---:|---:|---:|---:|---:|---:|");for(i,r)in rows.iter().enumerate(){println!("|{}|{}|{:.0}|{}|{}|{}|{:.0}|{:.1}%|{}/{}|",i+1,r.name,r.mean,r.median,r.p90,r.max,r.pt_mean,r.rmj_all,r.ok,r.failed)}println!("\n## 六张卡明细\n");for(i,r)in rows.iter().enumerate(){let d=r.cards.iter().map(|id|format!("{} {}",id,names.get(id).cloned().unwrap_or_else(||if *id==FRIEND{"[友]固定友人".into()}else{"?".into()}))).collect::<Vec<_>>().join(" / ");println!("{}. **{}**：{}",i+1,r.name,d)}Ok(())}
