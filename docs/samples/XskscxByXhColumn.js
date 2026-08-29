

	var columns ={
			zh:[
				 {title: '学号', key: 'XH',sortable:'true',width:80,fixed:'left'}
				,{title: '姓名', key: 'XM',sortable:'true',width:80,fixed:'left'}
				,{title: '课程代码', key: 'KCDM',sortable:'true',minWidth:100}
				,{title: '课程名称', key: 'KCMC',sortable:'true',minWidth:100
					,render:function (h, params)  {
					// if(params.row.PYLX=='1'){
					//
					// 	return h('span',params.row.KCMC) ;
					// }
					
					if(params.row.PYLX=='1'){
						
						return h('span',[h('span', {
                            style: { color: 'red' }},
                            "[本]")
                            ,[h('span',params.row.KCMC),h('span', { style: { color: 'red' }}, (params.row.CJBZMC&&params.row.CJBZMC!=null&&params.row.CJBZMC!=''?params.row.CJBZMC:'') )]
						] ) ;
					}



                    if(params.row.PYLX=='2'){

                        return h('span',[h('span', {
                            style: { color: 'red' }},
                            "[研]")
							,[h('span',params.row.KCMC),h('span', { style: { color: 'red' }}, (params.row.CJBZMC&&params.row.CJBZMC!=null&&params.row.CJBZMC!=''?params.row.CJBZMC:'') )]
                        ] ) ;
                    }
						
						
                    // if(params.row.PYLX=='2'){
                    //     return h('span',params.row.KCMC) ;
                    //
                    // }
						
					
				}
				
				}
				
				
				,{title: '考试类型', key: 'KSSJDMC',sortable:'true',minWidth:120
					,render:function (h, params)  {
						return h('span', params.row.KSSJDMC) ;
					}
				}
                ,{title: '座位号', key: 'ZWH',sortable:'true',width:120
                    ,hidden:( (pylx == '1' ? i18n('KSGL.BK.XSCX.SFXSZWH') : i18n('KSGL.YJS.XSCX.SFXSZWH')) == '1' ? false : true)
                }
				,{title: '考试时间', key: 'KSSJMS',sortable:'true',align:'center'
					// ,render:function (h, params)  {
					// 	var htmls = "第"+params.row.DJZ+"周 "+params.row.XQJMC+" "+params.row.KSJC+"-"+params.row.JSJC+"（小）节 "+(params.row.KSJTSJ==null||params.row.KSJTSJ==undefined?"":params.row.KSJTSJ);
					// 	if(params.row.DJZ==null){
					// 		htmls="---";
					// 	}
					// 	return h('span', htmls) ;
					// }

                    ,render:function (h, params)  {

                        var createElement ;
                        if(params.row.DJZ==null || params.row.DJZ==undefined){
                            createElement = h('span','---');
                        }else {
                            createElement = h('div', {},[
                                h('p',{ }
                                    ,[
                                        params.row.DJZ+"周 "+params.row.XQJMC+" "+params.row.KSRQ2
                                    ])
                                ,h('p',{ }
                                    ,[
                                        (params.row.KSJTSJ==null||params.row.KSJTSJ==undefined?"":params.row.KSJTSJ)
                                    ])
                            ]) ;
                        }

                        return createElement;
                    }


					,minWidth:300
				}
				,{title: '场地信息', key: 'CDDM',sortable:'true'
					,render:function (h, params)  {
						var htmls = params.row.JXLMC+" "+params.row.CDMC;
						return h('span', htmls) ;
					}
					,minWidth:300
				}
                ,{type:'html',title: '备注', key: 'JKJSBZ',minWidth:140}
				,{title: '开课院系', key: 'KKYXMC',sortable:'true',minWidth:160}		
				,{title: '专业', key: 'ZYMC',sortable:'true',minWidth:160}		
				,{title: '专业方向', key: 'ZYFXMC',sortable:'true',minWidth:160}
				,{title: '年级', key: 'NJMC',sortable:'true',minWidth:80}

			]
			,en:[
				 {title: 'Student ID', key: 'XH',width:160,fixed:'left'}
				,{title: 'Name', key: 'XM_EN',width:140,fixed:'left'}
				,{title: 'Course Code', key: 'KCDM',width:140}
				,{title: 'Course Title', key: 'KCMC_EN',width:300}
				,{title: 'Examination type', key: 'KSSJDMC',width:140
					,render:function (h, params)  {
						return h('span', params.row.KSSJD+"/"+params.row.KSSJDMC_EN) ;
					}
				}
				,{title: 'Date of Examination', key: 'KSSJMS',align:'center'
					,render:function (h, params)  {
						var createElement ;
						if(params.row.DJZ==null || params.row.DJZ==undefined){
							createElement = h('span','---');
						}else {
							createElement = h('div', {},[
								h('p',{ }
									,[
										params.row.KSRQ_EN
									])
								,h('p',{ }
									,[
										(params.row.KSJTSJ==null||params.row.KSJTSJ==undefined?"":params.row.KSJTSJ)
									])
							]) ;
						}

						return createElement;
					}
                	,width:300
            	}
				,{title: 'Location', key: 'CDXX'
					,render:function (h, params)  {
					var htmls = params.row.JXLMC_EN+" "+params.row.CDMC_EN;
					return h('span', htmls) ;
				}
				,minWidth:300}
					// ,{title: '开课院系', key: 'KKYXMC_EN',sortable:'true',width:160}
					// ,{title: '专业', key: 'ZYMC_EN',sortable:'true',width:160}
					// ,{title: '专业方向', key: 'ZYFXMC_EN',sortable:'true',width:160}
					// ,{title: '年级', key: 'NJMC_EN',sortable:'true',width:80}

			]
		}[global_language]
	
	



